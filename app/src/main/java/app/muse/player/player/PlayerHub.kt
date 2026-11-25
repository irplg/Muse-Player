package app.muse.player.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.muse.player.data.LyricLine
import app.muse.player.data.LyricRepo
import app.muse.player.data.Netease
import app.muse.player.data.Together
import app.muse.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/** 全局唯一的播放器：队列、播放状态、歌词都在这里；通知栏/锁屏控制通过 PlaybackService 暴露。 */
object PlayerHub {
    const val MODE_LOOP = 0
    const val MODE_SHUFFLE = 1
    const val MODE_ONE = 2

    lateinit var exo: ExoPlayer
        private set
    lateinit var sessionPlayer: Player
        private set

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var pendingAutoplay = true
    private var pendingStartMs = 0L
    val reportPositionMs: Long get() = pendingStartMs
    val reportPlayWhenReady: Boolean get() = pendingAutoplay
    private var controllerFuture: Any? = null

    @Volatile
    var applyingRemote = false
        private set
    private var remoteJob: Job? = null
    private var localRevision = 0L

    private fun localAction() {
        localRevision++
        remoteJob?.cancel() // A local action wins over an outstanding remote song lookup.
    }

    val queue = MutableStateFlow<List<Track>>(emptyList())
    val index = MutableStateFlow(-1)
    val current = MutableStateFlow<Track?>(null)
    val isPlaying = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val mode = MutableStateFlow(MODE_LOOP)
    val lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val errorMsg = MutableStateFlow<String?>(null)

    fun init(application: Application) {
        if (::exo.isInitialized) return
        app = application
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        exo = ExoPlayer.Builder(app)
            .setRenderersFactory(SpatialRenderersFactory(app))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
            .setAudioAttributes(audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        sessionPlayer = QueuePlayer(exo)

        // 空间音频开关变化时重新下发音频属性：交给系统空间化，或由软件处理器接管。
        SpatialAudio.onChanged = { exo.setAudioAttributes(audioAttributes(), true) }

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlayerHub.isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) durationMs.value = exo.duration.coerceAtLeast(0L)
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMsg.value = "播放失败：" + (error.cause?.message ?: error.errorCodeName)
            }
        })

        scope.launch {
            while (isActive) {
                positionMs.value = exo.currentPosition
                val d = exo.duration
                if (d > 0) durationMs.value = d
                delay(250)
            }
        }

        // 绑定 MediaSessionService，让它负责通知栏 / 锁屏控制 / 前台服务
        controllerFuture = MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java)),
        ).buildAsync()
    }

    /** 空间音频开启时允许系统做双耳渲染；关闭时明确拒绝。 */
    private fun audioAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        // 1 = AUTO（允许系统双耳渲染），0 = NEVER；与 android.media.AudioAttributes 取值一致。
        builder.setSpatializationBehavior(if (SpatialAudio.enabled.value) 1 else 0)
        return builder.build()
    }

    /** 在默认渲染器基础上换掉音频输出链，插入软件空间化处理器。 */
    private class SpatialRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(SpatialAudio.processor),
            )
            .build()
    }

    // ------------------------------------------------------------ queue control
    fun playQueue(list: List<Track>, start: Int) {
        localAction()
        queue.value = list
        playIndex(start)
    }

    /** 只替换队列、不开始播放（加入一起听房间时用） */
    fun setQueueSilently(list: List<Track>) {
        queue.value = list
    }

    fun playIndex(i: Int, startAtMs: Long = 0L, autoplay: Boolean = true) {
        val q = queue.value
        if (i !in q.indices) return
        if (!applyingRemote) localAction()
        val t = q[i]
        val previousSong = current.value?.neteaseId
        pendingAutoplay = autoplay
        pendingStartMs = startAtMs
        index.value = i
        current.value = t
        lyrics.value = null
        errorMsg.value = null
        buffering.value = true
        loadJob?.cancel()
        if (!applyingRemote) Together.report("GOTO", previousSong)
        loadJob = scope.launch {
            try {
                val uri = t.localUri ?: Netease.songUrl(t.neteaseId ?: throw IllegalStateException("无效歌曲"))
                val meta = MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .apply { sessionArtwork(t)?.let { setArtworkUri(Uri.parse(it)) } }
                    .build()
                val item = MediaItem.Builder().setMediaId(t.id).setUri(uri).setMediaMetadata(meta).build()
                exo.setMediaItem(item, pendingStartMs)
                exo.prepare()
                if (pendingAutoplay) exo.play() else exo.pause()
                launch {
                    val l = LyricRepo.forTrack(t)
                    if (current.value?.id == t.id) lyrics.value = l
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMsg.value = "无法播放：" + (e.message ?: "未知错误")
                buffering.value = false
            }
        }
    }

    private fun sessionArtwork(t: Track): String? {
        val a = t.artwork ?: return null
        return if (a.startsWith("http") || a.contains("albumart")) a else null
    }

    fun play() {
        if (loadJob?.isActive == true && current.value != null) {
            localAction()
            pendingAutoplay = true
            Together.report("PLAY")
            return
        }
        if (exo.mediaItemCount == 0) {
            if (queue.value.isNotEmpty()) playIndex(maxOf(index.value, 0))
            return
        }
        localAction()
        pendingAutoplay = true
        exo.play()
        Together.report("PLAY")
    }

    fun pause() {
        localAction()
        pendingAutoplay = false
        exo.pause()
        Together.report("PAUSE")
    }

    fun toggle() {
        if (exo.isPlaying) pause() else play()
    }

    fun seekTo(ms: Long) {
        localAction()
        pendingStartMs = ms.coerceAtLeast(0L)
        exo.seekTo(pendingStartMs)
        Together.report("SEEK")
    }

    fun next() {
        val q = queue.value
        if (q.isEmpty()) return
        val n = if (mode.value == MODE_SHUFFLE && q.size > 1) {
            var r: Int
            do {
                r = Random.nextInt(q.size)
            } while (r == index.value)
            r
        } else {
            (index.value + 1) % q.size
        }
        playIndex(n)
    }

    fun prev() {
        val q = queue.value
        if (q.isEmpty()) return
        if (exo.currentPosition > 3000) {
            seekTo(0)
            return
        }
        playIndex((index.value - 1 + q.size) % q.size)
    }

    fun cycleMode() {
        mode.value = (mode.value + 1) % 3
    }

    private fun onEnded() {
        if (mode.value == MODE_ONE) {
            exo.seekTo(0)
            exo.play()
        } else {
            next()
        }
    }

    // ------------------------------------------------------------ listen together

    /** 远端查询可以挂起；每次新指令都取消前一个，用户本地操作也不会被旧查询覆盖。 */
    fun applyRemote(songId: Long?, progress: Long?, playStatus: String?, forceSeek: Boolean = false) {
        remoteJob?.cancel()
        val revision = localRevision
        remoteJob = scope.launch {
            try {
                val validSong = songId?.takeIf { it > 0L }
                if (validSong != null && validSong != current.value?.neteaseId) {
                    var idx = queue.value.indexOfFirst { it.neteaseId == validSong }
                    if (idx < 0) {
                        val fetched = Netease.songDetails(listOf(validSong))
                        if (revision != localRevision) return@launch
                        if (fetched.isNotEmpty()) {
                            queue.value = queue.value + fetched
                            idx = queue.value.lastIndex
                        }
                    }
                    if (revision != localRevision) return@launch
                    if (idx >= 0) {
                        applyingRemote = true
                        try { playIndex(idx, progress ?: 0L, playStatus != "PAUSE") }
                        finally { applyingRemote = false }
                    }
                } else {
                    if (revision != localRevision) return@launch
                    applyingRemote = true
                    try {
                        if (progress != null && (forceSeek || abs(exo.currentPosition - progress) > 2500)) exo.seekTo(progress)
                        if (playStatus == "PAUSE" && exo.playWhenReady) exo.pause()
                        if (playStatus == "PLAY" && !exo.playWhenReady) exo.play()
                        if (playStatus == "PAUSE") pendingAutoplay = false
                        if (playStatus == "PLAY") pendingAutoplay = true
                        if (progress != null) pendingStartMs = progress
                    } finally { applyingRemote = false }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Netease.log("远端播放同步失败: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 让通知栏 / 锁屏的“上一首、下一首”也走我们自己的队列 */
    private class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        // 注意要写 PlayerHub.xxx：Player 自带 next()/previous()，不加前缀会调到被包装的 ExoPlayer 上
        override fun seekToNext() = PlayerHub.next()
        override fun seekToNextMediaItem() = PlayerHub.next()
        override fun seekToPrevious() = PlayerHub.prev()
        override fun seekToPreviousMediaItem() = PlayerHub.prev()
    }
}
