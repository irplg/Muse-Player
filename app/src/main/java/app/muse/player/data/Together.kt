package app.muse.player.data

import app.muse.player.player.PlayerHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 社区公开的 listen/together 协议实验实现；官方多人协议/易盾校验可能不兼容。
 * 这里不假定服务器一定返回播放指令，也不把心跳中的本机状态当作远端指令。
 */
object Together {
    data class Room(
        val roomId: String,
        val inviterId: String,
        val host: Boolean,
        val users: List<RoomUser> = emptyList(),
    )

    val room = MutableStateFlow<Room?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var seq = 1
    private var lastRemoteSeq = Long.MIN_VALUE
    private var lastRemoteKey: String? = null
    private var lastLocal: LocalCommand? = null
    private var generation = 0L
    private var hbMs = 3000L

    private data class LocalCommand(val seq: Int, val song: Long, val status: String, val progress: Long, val type: String)

    fun shareLink(r: Room): String {
        val song = PlayerHub.current.value?.neteaseId ?: 0L
        return "https://st.music.163.com/listen-together/share/?songId=$song&roomId=${r.roomId}&inviterId=${r.inviterId}"
    }

    /** 成功返回 null，失败返回错误文案 */
    suspend fun create(): String? {
        val me = Netease.profile.value ?: return "请先登录网易云"
        val j = Netease.ltCreate()
        val roomId = j.optJSONObject("data")?.optJSONObject("roomInfo")?.optString("roomId").orEmpty()
        if (roomId.isBlank()) return "创建房间失败：" + j.optInt("code", -1).toString()
        resetRoom(Room(roomId, me.userId.toString(), host = true))
        Netease.ltCheck(roomId)
        val ids = PlayerHub.queue.value.mapNotNull { it.neteaseId }
        if (ids.isNotEmpty()) Netease.ltSyncList(roomId, me.userId, seq++, ids)
        startLoop()
        // Publish the authoritative initial track immediately; otherwise a member joining
        // between playlist sync and the next user action only receives progress heartbeats.
        if (PlayerHub.current.value?.neteaseId != null) report("GOTO")
        return null
    }

    suspend fun join(input: String): String? {
        Netease.profile.value ?: return "请先登录网易云"
        val roomId = Regex("roomId=([^&\\s]+)").find(input)?.groupValues?.get(1)
            ?: return "没在链接里找到 roomId"
        val inviter = Regex("inviterId=([^&\\s]+)").find(input)?.groupValues?.get(1)
            ?: return "没在链接里找到 inviterId"
        val j = Netease.ltAccept(roomId, inviter)
        if (j.optInt("code") != 200) return "加入失败：" + j.optInt("code", -1).toString()
        val check = Netease.ltCheck(roomId)
        resetRoom(Room(roomId, inviter, host = false))
        val session = generation
        val ids = Netease.ltPlaylistIds(roomId)
        var tracks = if (ids.isNotEmpty()) Netease.songDetails(ids) else emptyList()
        if (session != generation) return "加入已取消"
        if (tracks.isNotEmpty()) PlayerHub.setQueueSilently(tracks)

        // Accept does not start playback. Read the authoritative room snapshot and
        // load its target song (which may be absent from the playlist during GOTO).
        val state = TogetherProtocol.find(check, allowStateFields = true)
            ?: TogetherProtocol.find(Netease.ltStatus(), allowStateFields = true)
        val target = state?.songId
        if (target != null && target > 0L && tracks.none { it.neteaseId == target }) {
            val extra = Netease.songDetails(listOf(target))
            if (session != generation) return "加入已取消"
            if (extra.isNotEmpty()) {
                tracks = tracks + extra
                PlayerHub.setQueueSilently(tracks)
            }
        }
        if (session == generation && state != null) {
            PlayerHub.applyRemote(state.songId, state.progressMs, state.status, state.type == "SEEK")
        }
        if (session == generation) startLoop()
        // The join response can carry the current playback command separately from
        // playlist state; apply it before the heartbeat loop starts.
        if (session == generation && state == null) {
            Netease.ltStatus().let { snapshot ->
                TogetherProtocol.find(snapshot, allowStateFields = true)?.let {
                    PlayerHub.applyRemote(it.songId, it.progressMs, it.status, it.type == "SEEK")
                }
            }
        }
        return null
    }

    private fun resetRoom(value: Room) {
        loop?.cancel()
        generation++
        seq = 1
        lastRemoteSeq = Long.MIN_VALUE
        lastRemoteKey = null
        lastLocal = null
        room.value = value
    }

    fun leave() {
        val r = room.value ?: return
        loop?.cancel()
        generation++
        room.value = null
        lastRemoteSeq = Long.MIN_VALUE
        lastRemoteKey = null
        lastLocal = null
        if (r.host) scope.launch { runCatching { Netease.ltEnd(r.roomId) } }
    }

    /** 在用户动作当下拍摄状态，不能等歌曲 URL 异步加载后再取位置/状态。 */
    fun report(type: String, formerSongId: Long? = null) {
        val r = room.value ?: return
        if (PlayerHub.applyingRemote) return
        val songId = PlayerHub.current.value?.neteaseId ?: return
        val command = type.uppercase()
        if (command !in setOf("PLAY", "PAUSE", "GOTO", "SEEK")) return
        val status = if (command == "PAUSE") "PAUSE" else if (command == "PLAY") "PLAY"
            else if (PlayerHub.reportPlayWhenReady) "PLAY" else "PAUSE"
        val progress = if (command == "GOTO") 0L else if (command == "SEEK") PlayerHub.reportPositionMs
            else PlayerHub.exo.currentPosition.coerceAtLeast(0L)
        val serial = seq++
        val session = generation
        lastLocal = LocalCommand(serial, songId, status, progress, command)
        scope.launch {
            if (session != generation || room.value?.roomId != r.roomId) return@launch
            try {
                val answer = Netease.ltPlayCommand(r.roomId, progress, command, songId, serial, status, formerSongId)
                if (session == generation && answer.optInt("code") != 200)
                    Netease.log("report $command 未成功: ${answer.optInt("code", -1)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == generation) Netease.log("report $command 失败: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun startLoop() {
        loop?.cancel()
        val session = generation
        loop = scope.launch {
            var tick = 0
            while (isActive && session == generation) {
                val r = room.value ?: break
                try {
                    val cur = PlayerHub.current.value
                    val j = Netease.ltHeartbeat(
                        r.roomId,
                        cur?.neteaseId ?: 0L,
                        if (PlayerHub.exo.playWhenReady) "PLAY" else "PAUSE",
                        PlayerHub.exo.currentPosition,
                    )
                    if (session != generation) break
                    if (tick % 5 == 0) Netease.log("心跳返回 ${j.optInt("code", -1)}")
                    val d = j.optJSONObject("data")?.optLong("heartBeatDuration", 0L) ?: 0L
                    if (d > 0) hbMs = d.coerceIn(2000L, 30000L)
                    applyRemote(j)
                    if (tick % 3 == 0) refreshUsers(r.roomId, session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Netease.log("一起听同步出错: ${e.javaClass.simpleName}")
                }
                tick++
                delay(hbMs)
            }
        }
    }

    private suspend fun refreshUsers(roomId: String, session: Long) {
        val arr = Netease.ltStatus().optJSONObject("data")?.optJSONObject("roomInfo")?.optJSONArray("roomUsers")
            ?: return
        if (session != generation || room.value?.roomId != roomId) return
        val users = (0 until arr.length()).map {
            val u = arr.getJSONObject(it)
            RoomUser(u.optLong("userId"), u.optString("nickname"), u.optString("avatarUrl").takeIf { it.isNotBlank() })
        }
        room.value = room.value?.copy(users = users)
    }

    /** 仅识别带明确 commandType 的播放指令；单纯的心跳回显不是指令。 */
    private fun applyRemote(j: JSONObject) {
        val state = TogetherProtocol.find(j) ?: return
        val type = state.type
        val me = Netease.profile.value?.userId ?: return
        val from = state.operatorId ?: -1L
        if (from == me) return
        val clientSeq = state.clientSeq ?: -1
        val local = lastLocal
        val serverSeq = state.serverSeq ?: -1L
        if (serverSeq >= 0 && serverSeq <= lastRemoteSeq) return
        val song = state.songId
        val progress = state.progressMs
        val status = state.status
        // A locally originated command can echo back without an operator ID.
        if (from < 0 && local != null && local.song == song && local.type == type &&
            (clientSeq == local.seq || (clientSeq < 0 && local.status == status && local.progress == progress))) return
        val key = if (serverSeq >= 0) "server:$serverSeq" else
            "${from}:${clientSeq}:${type}:${song}:${progress}:${status}"
        if (key == lastRemoteKey) return
        if (serverSeq >= 0) lastRemoteSeq = serverSeq
        lastRemoteKey = key
        Netease.log("收到房间播放指令 $type")
        PlayerHub.applyRemote(song, progress, status, type == "SEEK")
    }

    private fun deepFind(node: Any?, depth: Int, pred: (JSONObject) -> Boolean): JSONObject? {
        if (depth > 6) return null
        when (node) {
            is JSONObject -> {
                if (pred(node)) return node
                val keys = node.keys()
                while (keys.hasNext()) {
                    val value = node.opt(keys.next())
                    deepFind(value, depth + 1, pred)?.let { return it }
                    if (value is String && value.startsWith("{")) {
                        runCatching { JSONObject(value) }.getOrNull()?.let {
                            deepFind(it, depth + 1, pred)?.let { found -> return found }
                        }
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                deepFind(node.opt(i), depth + 1, pred)?.let { return it }
            }
        }
        return null
    }
}
