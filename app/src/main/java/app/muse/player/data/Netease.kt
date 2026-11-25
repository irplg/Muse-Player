package app.muse.player.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐非官方接口客户端（社区公开的 weapi / eapi 协议）。
 * 只使用你自己账号的权限：不含任何 VIP / 版权绕过。
 */
object Netease {
    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"
    private const val UA_IOS = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 17.4.1; zh_CN)"

    class LyricRaw(val lrc: String, val tlrc: String, val yrc: String)
    private class Reply(val json: JSONObject, val setCookies: List<String>)

    private lateinit var appCtx: Context
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val cookies = linkedMapOf<String, String>()
    private var deviceId = ""

    val profile = MutableStateFlow<Profile?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val loggedIn: Boolean get() = !cookies["MUSIC_U"].isNullOrBlank()

    private fun prefs() = appCtx.getSharedPreferences("netease", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        cookies.putAll(parseCookie(prefs().getString("cookie", "").orEmpty()))
        deviceId = prefs().getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs().edit().putString("deviceId", it).apply()
        }
        val saved = prefs().getString("profile", null)
        if (saved != null && loggedIn) {
            runCatching {
                val j = JSONObject(saved)
                profile.value = Profile(j.getLong("id"), j.getString("name"), j.optString("avatar").takeIf { it.isNotBlank() })
            }
        }
    }

    /** 原始服务端响应可能包含凭据、用户资料或邀请链接；不记录响应体。 */
    fun log(msg: String) {
        val safe = msg
            .replace(Regex("(?i)(MUSIC_U|MUSIC_A|__csrf|cookie|authorization|token|password|deviceId)\\s*[:=]\\s*[^,;\\s}]+"), "$1=[已隐藏]")
            .replace(Regex("https?://[^\\s]+"), "[链接已隐藏]")
            .take(220)
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.update { (listOf("$t  $safe") + it).take(120) }
    }

    fun exportLogs(): String = buildString {
        appendLine("Muse 请求诊断 · ${android.os.Build.VERSION.RELEASE}")
        appendLine("仅包含请求结果摘要；请在分享前检查内容。")
        logs.value.asReversed().forEach { appendLine(it) }
    }

    // ---------------------------------------------------------------- cookies

    fun parseCookie(s: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (part in s.split(';')) {
            val i = part.indexOf('=')
            if (i > 0) out[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        return out
    }

    private fun saveCookies() {
        prefs().edit().putString("cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }).apply()
    }

    private fun storeSetCookies(lines: List<String>) {
        for (line in lines) {
            val kv = line.substringBefore(';')
            val name = kv.substringBefore('=').trim()
            val value = kv.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
        }
        saveCookies()
    }

    private fun cookieString(): String {
        val m = linkedMapOf("os" to "pc", "appver" to "3.0.18.203152", "NMTID" to deviceId.take(24), "WEVNSM" to "1.0.0")
        m.putAll(cookies)
        return m.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // ---------------------------------------------------------------- transport

    private suspend fun post(url: String, form: Map<String, String>, cookie: String, ua: String): Reply =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { for ((k, v) in form) add(k, v) }.build()
            val req = Request.Builder().url(url).post(body)
                .header("User-Agent", ua)
                .header("Referer", "https://music.163.com")
                .header("Cookie", cookie)
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject().put("code", -1).put("raw", text.take(200))
                }
                Reply(json, r.headers("Set-Cookie"))
            }
        }

    private suspend fun weapiRaw(path: String, params: Map<String, Any?> = emptyMap()): Reply {
        val data = JSONObject(params).put("csrf_token", cookies["__csrf"].orEmpty())
        return post("https://music.163.com/weapi$path", NeteaseCrypto.weapi(data.toString()), cookieString(), UA_PC)
    }

    /** 网页端协议。path 不带 /weapi 前缀，例如 /user/playlist */
    suspend fun weapi(path: String, params: Map<String, Any?> = emptyMap()): JSONObject =
        weapiRaw(path, params).json

    /** 客户端协议。apiPath 形如 /api/song/lyric/v1 */
    suspend fun eapi(
        apiPath: String,
        params: Map<String, Any?> = emptyMap(),
        host: String = "interface.music.163.com",
    ): JSONObject {
        val header = linkedMapOf(
            "osver" to "17.4.1",
            "deviceId" to deviceId,
            "appver" to "9.0.90",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (System.currentTimeMillis() / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to cookies["__csrf"].orEmpty(),
            "os" to "ios",
            "channel" to "",
            "requestId" to "${System.currentTimeMillis()}_${(0..999).random().toString().padStart(4, '0')}",
        )
        cookies["MUSIC_U"]?.let { header["MUSIC_U"] = it }
        cookies["MUSIC_A"]?.let { header["MUSIC_A"] = it }
        val data = JSONObject(params).put("header", JSONObject(header as Map<*, *>))
        val enc = NeteaseCrypto.eapi(apiPath, data.toString())
        val cookie = header.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return post("https://$host/eapi" + apiPath.removePrefix("/api"), mapOf("params" to enc), cookie, UA_IOS).json
    }

    // ---------------------------------------------------------------- login

    suspend fun qrKey(): String = weapi("/login/qrcode/unikey", mapOf("type" to 1)).optString("unikey")

    fun qrUrl(key: String) = "https://music.163.com/login?codekey=$key"

    /** 800 过期 / 801 等待扫码 / 802 已扫码待确认 / 803 成功 */
    suspend fun qrCheck(key: String): Int {
        val r = weapiRaw("/login/qrcode/client/login", mapOf("key" to key, "type" to 1))
        val code = r.json.optInt("code", -1)
        if (code == 803) {
            storeSetCookies(r.setCookies)
            refreshProfile()
        }
        return code
    }

    suspend fun loginWithCookie(text: String): Boolean {
        val parsed = if (text.contains("=")) parseCookie(text) else mapOf("MUSIC_U" to text.trim())
        cookies.clear()
        cookies.putAll(parsed)
        saveCookies()
        val p = runCatching { refreshProfile() }.getOrNull()
        if (p == null) {
            logout()
            return false
        }
        return true
    }

    suspend fun refreshProfile(): Profile? {
        if (!loggedIn) {
            profile.value = null
            return null
        }
        val j = weapi("/nuser/account/get")
        val p = j.optJSONObject("profile")
        if (p == null) {
            log("账户接口返回 ${j.optInt("code", -1)}")
            return null
        }
        val pr = Profile(p.optLong("userId"), p.optString("nickname"), p.optString("avatarUrl").takeIf { it.isNotBlank() })
        profile.value = pr
        prefs().edit().putString(
            "profile",
            JSONObject().put("id", pr.userId).put("name", pr.nickname).put("avatar", pr.avatar ?: "").toString(),
        ).apply()
        return pr
    }

    fun logout() {
        cookies.clear()
        prefs().edit().remove("cookie").remove("profile").apply()
        profile.value = null
    }

    // ---------------------------------------------------------------- library

    private fun parseSong(o: JSONObject): Track {
        val ar = o.optJSONArray("ar") ?: o.optJSONArray("artists")
        val artist = (0 until (ar?.length() ?: 0)).joinToString(" / ") { ar!!.getJSONObject(it).optString("name") }
        val al = o.optJSONObject("al") ?: o.optJSONObject("album")
        val id = o.optLong("id")
        return Track(
            id = "ne:$id",
            title = o.optString("name"),
            artist = artist,
            album = al?.optString("name").orEmpty(),
            durationMs = o.optLong("dt", o.optLong("duration")),
            artwork = al?.optString("picUrl")?.takeIf { it.isNotBlank() },
            neteaseId = id,
        )
    }

    internal data class UserPlaylists(val loved: PlaylistInfo?, val all: List<PlaylistInfo>)

    internal suspend fun userPlaylists(uid: Long): UserPlaylists {
        val j = weapi("/user/playlist", mapOf("uid" to uid, "limit" to 1000, "offset" to 0, "includeVideo" to true))
        val arr = j.optJSONArray("playlist") ?: error("用户歌单不可用")
        var loved: PlaylistInfo? = null
        val playlists = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optLong("id")
            if (id <= 0) return@mapNotNull null
            val info = PlaylistInfo(id, p.optString("name"), p.coverUrl(), p.optInt("trackCount"))
            val owner = p.optLong("userId").takeIf { it > 0 } ?: p.optJSONObject("creator")?.optLong("userId")
            // 5 是账户的「我喜欢的音乐」，不是名字恰好相同的普通歌单或订阅歌单。
            if (p.optInt("specialType") == 5 && owner == uid && !p.optBoolean("subscribed")) loved = info
            info
        }
        return UserPlaylists(loved, playlists.distinctBy { it.id })
    }

    suspend fun myPlaylists(): List<PlaylistInfo> = profile.value?.userId?.let { userPlaylists(it).all }.orEmpty()

    private fun JSONObject.coverUrl(): String? =
        optString("coverImgUrl").takeUnless { it.isBlank() || it == "null" }

    private suspend fun playlistTrackIds(id: Long, limit: Int): List<Long> {
        for (path in listOf("/v6/playlist/detail", "/v3/playlist/detail")) {
            val j = weapi(path, mapOf("id" to id, "n" to 100000, "s" to 8))
            val arr = j.optJSONObject("playlist")?.optJSONArray("trackIds")
            if (arr != null) {
                return (0 until minOf(arr.length(), limit)).mapNotNull { i ->
                    arr.optJSONObject(i)?.optLong("id")?.takeIf { it > 0 }
                }
            }
            log("歌单接口 $path 返回 ${j.optInt("code", -1)}")
        }
        error("歌单曲目不可用")
    }

    suspend fun playlistTracks(id: Long): List<Track> = songDetails(playlistTrackIds(id, limit = 1000))

    /** 与详情使用相同的曲序，只取第一首歌，不使用歌单自己的封面。 */
    suspend fun playlistFirstTrackArtwork(id: Long): String? =
        songDetails(playlistTrackIds(id, limit = 1)).firstOrNull()?.artwork?.takeUnless { it == "null" }

    suspend fun songDetails(ids: List<Long>): List<Track> {
        val out = ArrayList<Track>()
        for (chunk in ids.chunked(300)) {
            val c = chunk.joinToString(",", "[", "]") { "{\"id\":$it}" }
            val arr = weapi("/v3/song/detail", mapOf("c" to c)).optJSONArray("songs") ?: continue
            for (i in 0 until arr.length()) out.add(parseSong(arr.getJSONObject(i)))
        }
        val order = ids.withIndex().associate { it.value to it.index }
        return out.sortedBy { order[it.neteaseId] ?: Int.MAX_VALUE }
    }

    suspend fun dailySongs(): List<Track> {
        val arr = weapi("/v3/discovery/recommend/songs").optJSONObject("data")?.optJSONArray("dailySongs")
            ?: return emptyList()
        return (0 until arr.length()).map { parseSong(arr.getJSONObject(it)) }
    }

    /** 每日推荐歌单；只展示接口实际返回的条目。 */
    suspend fun recommendedPlaylists(): List<PlaylistInfo> {
        val j = weapi("/v1/discovery/recommend/resource")
        val arr = j.optJSONArray("recommend") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optLong("id")
            if (id <= 0) null else PlaylistInfo(
                id, p.optString("name"),
                p.optString("picUrl").ifBlank { p.optString("coverImgUrl") }.takeIf { it.isNotBlank() },
                p.optInt("trackCount"),
            )
        }
    }

    /** 来自网易云的公开精品歌单，访客也可查看；并非全部由官方账号创建。 */
    suspend fun publicPlaylists(limit: Int = 12): List<PlaylistInfo> {
        val j = weapi("/playlist/highquality/list", mapOf("cat" to "全部", "limit" to limit, "lasttime" to 0, "total" to true))
        val arr = j.optJSONArray("playlists") ?: error("公开歌单不可用")
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optLong("id")
            val name = p.optString("name")
            if (id <= 0 || name.isBlank() || p.optInt("privacy") == 10) null
            else PlaylistInfo(id, name, p.coverUrl(), p.optInt("trackCount"))
        }.distinctBy { it.id }
    }

    /** 私人雷达：通过用户实际歌单中的雷达条目匹配，不合成虚假的歌单。 */
    fun radarPlaylist(playlists: List<PlaylistInfo>): PlaylistInfo? =
        playlists.firstOrNull { it.name.contains("私人雷达") || it.name.contains("雷达歌单") }

    suspend fun search(q: String, limit: Int = 30): List<Track> {
        val j = weapi(
            "/cloudsearch/get/web",
            mapOf("s" to q, "type" to 1, "limit" to limit, "offset" to 0, "total" to true),
        )
        val arr = j.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until arr.length()).map { parseSong(arr.getJSONObject(it)) }
    }

    /** 各类型走独立的网易云搜索，不用歌曲名下的字符串拼出歌手或专辑。 */
    suspend fun searchMusic(q: String, limit: Int = 30): MusicSearchResults = coroutineScope {
        val songs = async {
            searchResultType(q, limit, type = 1, key = "songs")
                ?.filter { it.optLong("id") > 0 }?.distinctBy { it.optLong("id") }
        }
        val artists = async {
            searchResultType(q, limit, type = 100, key = "artists")
                ?.mapNotNull(::parseArtist)?.distinctBy { it.id }
        }
        val albums = async {
            searchResultType(q, limit, type = 10, key = "albums")
                ?.mapNotNull(::parseAlbum)?.distinctBy { it.id }
        }
        val foundSongs = songs.await()
        val foundArtists = artists.await()
        val foundAlbums = albums.await()
        MusicSearchResults(
            tracks = foundSongs.orEmpty().mapNotNull(::parseSearchTrack),
            artists = foundArtists.orEmpty(),
            albums = foundAlbums.orEmpty(),
            unavailable = buildSet {
                if (foundSongs == null) add(MusicSearchCategory.Songs)
                if (foundArtists == null) add(MusicSearchCategory.Artists)
                if (foundAlbums == null) add(MusicSearchCategory.Albums)
            },
            firstTrackArtist = foundSongs?.firstOrNull()?.let(::firstSearchTrackArtist),
        )
    }

    private fun firstSearchTrackArtist(song: JSONObject): ArtistInfo? {
        val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
        // Index zero is intentional: a missing first artist must not promote a collaborator.
        val first = artists?.optJSONObject(0) ?: return null
        val id = first.optLong("id").takeIf { it > 0 } ?: return null
        return ArtistInfo(id, first.text("name").orEmpty(), artwork = null)
    }

    /** Optional enrichment: the caller can render search results before starting this request. */
    internal suspend fun searchArtistSpotlight(id: Long): ArtistInfo? = try {
        artistDetail(id).artist
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("搜索首位歌手详情失败: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun searchResultType(q: String, limit: Int, type: Int, key: String): List<JSONObject>? = try {
        val reply = weapi(
            "/cloudsearch/pc",
            mapOf("s" to q, "type" to type, "limit" to limit, "offset" to 0, "total" to true),
        )
        check(reply.optInt("code") == 200) { "搜索接口返回 ${reply.optInt("code", -1)}" }
        reply.optJSONObject("result")?.optJSONArray(key).objects()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("搜索类型 $type 失败: ${e.javaClass.simpleName}")
        null
    }

    suspend fun artistDetail(id: Long): ArtistDetail {
        require(id > 0)
        val reply = weapi("/v1/artist/$id")
        check(reply.optInt("code") == 200) { "歌手详情接口返回 ${reply.optInt("code", -1)}" }
        val raw = reply.getJSONObject("artist")
        val artist = checkNotNull(parseArtist(raw)?.takeIf { it.id == id }) { "歌手详情 ID 不匹配" }
        val tracks = reply.optJSONArray("hotSongs").objects().mapNotNull(::parseSearchTrack).distinctBy { it.id }
        return ArtistDetail(artist, raw.text("briefDesc").orEmpty(), tracks)
    }

    suspend fun albumDetail(id: Long): AlbumDetail {
        require(id > 0)
        val reply = weapi("/v1/album/$id")
        check(reply.optInt("code") == 200) { "专辑详情接口返回 ${reply.optInt("code", -1)}" }
        val raw = reply.getJSONObject("album")
        val album = checkNotNull(parseAlbum(raw)?.takeIf { it.id == id }) { "专辑详情 ID 不匹配" }
        val tracks = (reply.optJSONArray("songs") ?: raw.optJSONArray("songs")).objects()
            .mapNotNull(::parseSearchTrack).distinctBy { it.id }
            .map { track -> track.copy(
                album = track.album.ifBlank { album.name },
                artwork = track.artwork ?: album.artwork,
            ) }
        return AlbumDetail(album, raw.text("description").orEmpty(), tracks)
    }

    private fun parseSearchTrack(song: JSONObject): Track? {
        if (song.optLong("id") <= 0) return null
        val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
        // Drop malformed entries for display only; the spotlight still reads index zero of the original JSON.
        if (artists != null && (0 until artists.length()).any { artists.optJSONObject(it) == null }) {
            return parseSong(JSONObject(song.toString()).put("ar", JSONArray(artists.objects())))
        }
        return parseSong(song)
    }

    private fun parseArtist(raw: JSONObject): ArtistInfo? {
        val id = raw.optLong("id").takeIf { it > 0 } ?: return null
        val name = raw.text("name") ?: return null
        return ArtistInfo(id, name, raw.text("picUrl") ?: raw.text("img1v1Url"))
    }

    private fun parseAlbum(raw: JSONObject): AlbumInfo? {
        val id = raw.optLong("id").takeIf { it > 0 } ?: return null
        val name = raw.text("name") ?: return null
        val artist = raw.optJSONArray("artists").objects().mapNotNull { it.text("name") }
            .joinToString(" / ").ifBlank { raw.optJSONObject("artist")?.text("name").orEmpty() }
        return AlbumInfo(id, name, raw.text("picUrl") ?: raw.text("blurPicUrl"), artist)
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** 给本地歌曲按“歌名 歌手”匹配一首网易云歌曲（用于拉歌词），名字对不上就返回 null。 */
    suspend fun matchId(title: String, artist: String): Long? {
        val hit = search("$title $artist", 5).firstOrNull() ?: return null
        val a = hit.title.lowercase()
        val b = title.lowercase()
        return if (a.contains(b) || b.contains(a)) hit.neteaseId else null
    }

    // ---------------------------------------------------------------- playback url / lyrics

    suspend fun songUrl(id: Long): String {
        runCatching {
            val j = eapi(
                "/api/song/enhance/player/url/v1",
                mapOf("ids" to "[$id]", "level" to "exhigh", "encodeType" to "flac"),
            )
            val d = j.optJSONArray("data")?.optJSONObject(0)
            val u = d?.optString("url").orEmpty()
            if (u.isNotBlank() && u != "null") return u.replace("http://", "https://")
            log("播放地址不可用，接口返回 ${j.optInt("code", -1)}")
        }.onFailure { log("player/url 失败: ${it.javaClass.simpleName}") }

        runCatching {
            val j = weapi("/song/enhance/player/url", mapOf("ids" to "[$id]", "br" to 320000))
            val u = j.optJSONArray("data")?.optJSONObject(0)?.optString("url").orEmpty()
            if (u.isNotBlank() && u != "null") return u.replace("http://", "https://")
        }
        // 最后兜底：公开的外链重定向（只有免费可听的歌能播）
        return "https://music.163.com/song/media/outer/url?id=$id.mp3"
    }

    suspend fun lyric(id: Long): LyricRaw {
        runCatching {
            val j = eapi(
                "/api/song/lyric/v1",
                mapOf("id" to id, "cp" to false, "tv" to 0, "lv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0),
                host = "interface3.music.163.com",
            )
            val raw = LyricRaw(
                j.optJSONObject("lrc")?.optString("lyric").orEmpty(),
                j.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
                j.optJSONObject("yrc")?.optString("lyric").orEmpty(),
            )
            if (raw.lrc.isNotBlank() || raw.yrc.isNotBlank()) return raw
        }.onFailure { log("lyric/v1 失败: ${it.javaClass.simpleName}") }

        val j = weapi("/song/lyric", mapOf("id" to id, "lv" to -1, "tv" to -1, "rv" to -1, "kv" to -1))
        return LyricRaw(
            j.optJSONObject("lrc")?.optString("lyric").orEmpty(),
            j.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
            "",
        )
    }

    // ---------------------------------------------------------------- 一起听（listen together，社区公开接口，实验性）

    private suspend fun eapiFirstOk(path: String, variants: List<Map<String, Any?>>): JSONObject {
        var last = JSONObject()
        for (v in variants) {
            last = eapi(path, v)
            log("$path 返回 ${last.optInt("code", -1)}")
            if (last.optInt("code") == 200) return last
        }
        return last
    }

    suspend fun ltCreate(): JSONObject =
        eapiFirstOk("/api/listen/together/room/create", listOf(mapOf("refer" to "songplay_more")))

    suspend fun ltCheck(room: String): JSONObject =
        eapiFirstOk("/api/listen/together/room/check", listOf(mapOf("roomId" to room)))

    suspend fun ltAccept(room: String, inviter: String): JSONObject =
        eapiFirstOk(
            "/api/listen/together/play/invitation/accept",
            listOf(mapOf("refer" to "inbox_invite", "roomId" to room, "inviterId" to inviter)),
        )

    suspend fun ltStatus(): JSONObject = weapi("/listen/together/status/get")

    suspend fun ltPlaylistIds(room: String): List<Long> {
        val j = eapiFirstOk("/api/listen/together/sync/playlist/get", listOf(mapOf("roomId" to room)))
        val arr = j.optJSONObject("data")?.optJSONObject("playlist")?.optJSONObject("displayList")?.optJSONArray("result")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).toLongOrNull() }
    }

    suspend fun ltSyncList(room: String, userId: Long, version: Int, ids: List<Long>): JSONObject {
        val list = ids.map(Long::toString)
        // Community API expects playlistParam, not commandInfo or flattened fields.
        val param = JSONObject()
            .put("commandType", "REPLACE")
            .put("version", org.json.JSONArray().put(JSONObject().put("userId", userId).put("version", version)))
            .put("anchorSongId", "").put("anchorPosition", -1)
            .put("randomList", org.json.JSONArray(list)).put("displayList", org.json.JSONArray(list)).toString()
        return eapiFirstOk(
            "/api/listen/together/sync/list/command/report",
            listOf(mapOf("roomId" to room, "playlistParam" to param)),
        )
    }

    suspend fun ltPlayCommand(
        room: String,
        progress: Long,
        type: String,
        songId: Long,
        seq: Int,
        status: String,
        formerSongId: Long? = null,
    ): JSONObject {
        val former = (formerSongId ?: -1L).toString()
        val info = JSONObject()
            .put("commandType", type).put("progress", progress).put("formerSongId", former)
            .put("targetSongId", songId.toString()).put("clientSeq", seq).put("playStatus", status).toString()
        return eapiFirstOk(
            "/api/listen/together/play/command/report",
            listOf(
                mapOf(
                    "roomId" to room, "progress" to progress, "commandType" to type, "formerSongId" to former,
                    "targetSongId" to songId.toString(), "clientSeq" to seq, "playStatus" to status,
                ),
                mapOf("roomId" to room, "commandInfo" to info),
            ),
        )
    }

    suspend fun ltHeartbeat(room: String, songId: Long, status: String, progress: Long): JSONObject =
        eapi(
            "/api/listen/together/heartbeat",
            mapOf("roomId" to room, "songId" to songId.toString(), "playStatus" to status, "progress" to progress),
        )

    suspend fun ltEnd(room: String): JSONObject =
        eapiFirstOk("/api/listen/together/end/v2", listOf(mapOf("roomId" to room)))
}
