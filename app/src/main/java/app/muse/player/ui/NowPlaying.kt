package app.muse.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.nevoit.sfcompose.symbols.StaticSymbol
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muse.player.data.LyricLine
import app.muse.player.data.Track
import app.muse.player.data.Together
import app.muse.player.player.PlayerHub
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun NowPlayingScreen(onClose: () -> Unit, onOpenAccount: () -> Unit, closeSwipe: Modifier = Modifier) {
    val track by PlayerHub.current.collectAsState()
    val t = track
    if (t == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val playing by PlayerHub.isPlaying.collectAsState()
    val pos by PlayerHub.positionMs.collectAsState()
    val dur by PlayerHub.durationMs.collectAsState()
    val mode by PlayerHub.mode.collectAsState()
    val lines by PlayerHub.lyrics.collectAsState()
    val err by PlayerHub.errorMsg.collectAsState()
    val room by Together.room.collectAsState()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showTogether by rememberSaveable { mutableStateOf(false) }
    var togetherButtonBounds by remember { mutableStateOf<Rect?>(null) }
    val reducedMotion = rememberReducedPlayerMotion()
    val texture = rememberArtTexture(t.artwork)
    val duration = if (dur > 0) dur else t.durationMs
    val fluidStrength = if (reducedMotion) 0.7f else 1f

    val dismissSwipe = if (showTogether) Modifier else closeSwipe
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp || (maxWidth >= 480.dp && maxWidth > maxHeight)
        val compact = maxHeight < 460.dp
        // 一起听打开时，播放页整体退到模糊层后面；弹窗本身保持清晰。
        Box(
            Modifier.fillMaxSize().then(
                if (showTogether) Modifier.blur(20.dp) else Modifier,
            ),
        ) {
            FluidArtworkBackground(texture, playing && !reducedMotion, fluidStrength)
            Column(
                Modifier.fillMaxSize().systemBarsPadding().then(
                    if (showTogether) Modifier.semantics { hideFromAccessibility() }
                    else Modifier.semantics {
                        customActions = listOf(CustomAccessibilityAction("关闭播放页") { onClose(); true })
                    },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 仅让非交互区域处理下拖，歌词列表、进度条和弹窗保留自己的手势。
                PlayerHeader(compact, dismissSwipe)

                val playbackControls: @Composable () -> Unit = {
                    PlaybackControls(
                        playing = playing,
                        pos = pos,
                        duration = duration,
                        mode = mode,
                        lyrics = showLyrics,
                        together = room != null || showTogether,
                        reducedMotion = reducedMotion,
                        compact = compact,
                        onLyrics = { showLyrics = !showLyrics },
                        togetherButtonModifier = Modifier.onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInRoot()
                            togetherButtonBounds = Rect(topLeft, coordinates.size.toSize())
                        },
                        onTogether = { showTogether = true },
                    )
                }
                if (wide) {
                    Row(
                        Modifier.widthIn(max = 1160.dp).fillMaxWidth().weight(1f)
                            .padding(horizontal = if (compact) 10.dp else 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlayerVisualPane(
                            showLyrics, t, lines, playing, err, reducedMotion, wide = true,
                            modifier = Modifier.weight(1.12f).fillMaxHeight(),
                            dismissSwipe = dismissSwipe,
                        )
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Column(
                                Modifier.widthIn(max = 460.dp).fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().then(dismissSwipe)
                                        .padding(horizontal = 28.dp, vertical = if (compact) 2.dp else 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (showLyrics) {
                                        Art(t.artwork, Modifier.size(if (compact) 44.dp else 58.dp), RoundedCornerShape(12.dp), px = 200)
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    TrackDetails(t, err, compact = compact)
                                }
                                playbackControls()
                            }
                        }
                    }
                } else {
                    PlayerVisualPane(
                        showLyrics, t, lines, playing, err, reducedMotion, wide = false,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        dismissSwipe = dismissSwipe,
                    )
                    TrackDetails(
                        t, err, compact = compact,
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = if (compact) 2.dp else 8.dp, bottom = 2.dp),
                    )
                    playbackControls()
                    Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                }
            }
        }
        TogetherPopup(
            expanded = showTogether,
            wide = wide,
            reducedMotion = reducedMotion,
            artwork = t.artwork,
            buttonBounds = togetherButtonBounds,
            onDismiss = { showTogether = false },
            onOpenAccount = {
                showTogether = false
                onOpenAccount()
            },
        )
    }
}

@Composable
private fun PlayerVisualPane(
    showLyrics: Boolean,
    track: Track,
    lines: List<LyricLine>?,
    playing: Boolean,
    error: String?,
    reducedMotion: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
    dismissSwipe: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = showLyrics,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                val direction = if (targetState) 1 else -1
                (fadeIn(tween(360)) +
                    slideInVertically(tween(420)) { direction * it / 9 } +
                    scaleIn(tween(420), initialScale = if (targetState) 1.06f else .88f)) togetherWith
                    (fadeOut(tween(240)) +
                        slideOutVertically(tween(350)) { -direction * it / 9 } +
                        scaleOut(tween(350), targetScale = if (targetState) .88f else 1.06f))
            }
        },
        label = "nowPlayingMode",
    ) { lyricsMode ->
        if (lyricsMode) LyricsPane(track, lines, reducedMotion, showTrackHeader = !wide, dismissSwipe = dismissSwipe)
        else ArtworkPane(track, playing, error, reducedMotion, artworkOnly = wide, dismissSwipe = dismissSwipe)
    }
}

@Composable
private fun PlaybackControls(
    playing: Boolean,
    pos: Long,
    duration: Long,
    mode: Int,
    lyrics: Boolean,
    together: Boolean,
    reducedMotion: Boolean,
    compact: Boolean,
    onLyrics: () -> Unit,
    togetherButtonModifier: Modifier,
    onTogether: () -> Unit,
) {
    SeekBar(pos, duration, playing, reducedMotion, compact)
    val controlPadding = if (compact) 16.dp else 36.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = controlPadding, vertical = if (compact) 2.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleGlyph(Glyphs.Prev, "上一首", compact) { PlayerHub.prev() }
        PlayPauseButton(playing, reducedMotion, compact)
        CircleGlyph(Glyphs.Next, "下一首", compact) { PlayerHub.next() }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = controlPadding, vertical = if (compact) 2.dp else 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleGlyph(Glyphs.Lyrics, Glyphs.LyricsFill, "歌词", lyrics, compact, onClick = onLyrics)
        ToggleGlyph(
            when (mode) {
                PlayerHub.MODE_SHUFFLE -> Glyphs.Shuffle
                PlayerHub.MODE_ONE -> Glyphs.RepeatOne
                else -> Glyphs.Loop
            },
            when (mode) {
                PlayerHub.MODE_SHUFFLE -> Glyphs.Shuffle
                PlayerHub.MODE_ONE -> Glyphs.RepeatOne
                else -> Glyphs.Loop
            },
            when (mode) {
                PlayerHub.MODE_SHUFFLE -> "播放模式：随机播放"
                PlayerHub.MODE_ONE -> "播放模式：单曲循环"
                else -> "播放模式：列表循环"
            },
            mode != PlayerHub.MODE_LOOP,
            compact,
        ) { PlayerHub.cycleMode() }
        ToggleGlyph(Glyphs.Together, Glyphs.TogetherFill, "一起听", together, compact, togetherButtonModifier, onTogether)
    }
}

/** 系统动画关闭、无障碍减少动态效果或省电模式下，使用静态背景和无过渡。 */
@Composable
internal fun rememberReducedPlayerMotion(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val animatorOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = remember(context) {
        Build.VERSION.SDK_INT >= 34 && runCatching {
            Settings.Secure.getInt(context.contentResolver, "accessibility_reduce_motion", 0) != 0
        }.getOrDefault(false)
    }
    return animatorOff || reduceMotion || power?.isPowerSaveMode == true
}

@Composable
private fun ArtworkPane(
    t: Track,
    playing: Boolean,
    err: String?,
    reducedMotion: Boolean,
    artworkOnly: Boolean,
    dismissSwipe: Modifier,
) {
    // 暂停时封面缩小，播放时弹回来。
    val scale by animateFloatAsState(
        if (playing) 1f else 0.84f,
        if (reducedMotion) tween(0) else spring(dampingRatio = 0.55f, stiffness = 260f),
        label = "artScale",
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().then(dismissSwipe).padding(horizontal = if (artworkOnly) 20.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val artSize = if (artworkOnly) minOf(maxWidth, maxHeight * 0.9f, 460.dp)
            else minOf(maxWidth, maxHeight * 0.74f)
        val compactArt = maxHeight < 320.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Art(
                t.artwork,
                Modifier
                    .size(artSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(14.dp, RoundedCornerShape(8.dp)),
                RoundedCornerShape(8.dp),
                px = 900,
            )
        }
    }
}

@Composable
private fun TrackDetails(t: Track, err: String?, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            t.title,
            fontSize = if (compact) 21.sp else 23.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            t.artist,
            fontSize = if (compact) 15.sp else 17.sp,
            color = Color.White.copy(alpha = 0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (err != null) {
            Text(err, fontSize = 13.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LyricsPane(
    t: Track,
    lines: List<LyricLine>?,
    reducedMotion: Boolean,
    showTrackHeader: Boolean,
    dismissSwipe: Modifier,
) {
    Column(Modifier.fillMaxSize()) {
        if (showTrackHeader) {
            Row(
                Modifier.fillMaxWidth().then(dismissSwipe).padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(t.artwork, Modifier.size(52.dp), RoundedCornerShape(6.dp), px = 200)
                Spacer(Modifier.width(14.dp))
                TrackDetails(t, err = null, compact = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                lines == null -> CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White)
                lines.isEmpty() -> Text("暂无歌词", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
                else -> AppleLyrics(lines, Modifier.fillMaxSize(), reducedMotion = reducedMotion)
            }
        }
    }
}

/** 播放进度条 + 自己实现的点按/拖动跳转 */
@Composable
private fun SeekBar(pos: Long, dur: Long, playing: Boolean, reducedMotion: Boolean, compact: Boolean) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    val base = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val smooth by animateFloatAsState(base, tween(if (reducedMotion) 0 else 260, easing = LinearEasing), label = "seek")

    // 松手/点击后把显示进度钉在目标位置，等播放器与平滑值都到位再交还，避免先回弹再跳过去。
    val latestSmooth by rememberUpdatedState(smooth)
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        withTimeoutOrNull(2000) {
            snapshotFlow { latestSmooth }.first { abs(it - target) < 0.01f }
        }
        if (pending == target) {
            pending = null
            drag = null
        }
    }

    val frac = drag ?: pending ?: smooth
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 28.dp else 36.dp)
                .pointerInput(dur) {
                    detectTapGestures { o ->
                        if (dur > 0) {
                            val target = (o.x / size.width).coerceIn(0f, 1f)
                            pending = target
                            PlayerHub.seekTo((target * dur).toLong())
                        }
                    }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            pending = null
                            drag = (o.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val target = drag
                            drag = null
                            if (target != null && dur > 0) {
                                pending = target
                                PlayerHub.seekTo((target * dur).toLong())
                            }
                        },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 暂停时始终是静止的直线，拖动也不起波浪；只有真正在播放才让波形动。
            WaveProgress(frac, moving = playing, reducedMotion = reducedMotion)
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt((frac * dur).toLong()), fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
            Spacer(Modifier.weight(1f))
            Text(
                "-" + fmt(dur - (frac * dur).toLong()),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun CircleGlyph(d: StaticSymbol, label: String, compact: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (compact) 44.dp else 54.dp).clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(d, tint = Color.White, size = if (compact) 26.dp else 32.dp) }
}

/** 播放键：跟 Apple Music 一样只留白色图标，不加圆形底。 */
@Composable
private fun PlayPauseButton(playing: Boolean, reducedMotion: Boolean, compact: Boolean) {
    Box(
        Modifier
            .size(if (compact) 58.dp else 76.dp)
            .clip(CircleShape)
            .semantics { contentDescription = if (playing) "暂停" else "播放" }
            .clickable(role = Role.Button) { PlayerHub.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        PlayPauseGlyph(
            playing = playing,
            tint = Color.White,
            size = if (compact) 40.dp else 50.dp,
            reducedMotion = reducedMotion,
        )
    }
}

@Composable
private fun ToggleGlyph(
    d: StaticSymbol,
    dSelected: StaticSymbol,
    label: String,
    on: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(if (compact) 44.dp else 46.dp).clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (on) dSelected else d, tint = Color.White, size = 24.dp) }
}

/**
 * 播放页顶部：只留 iOS 的下拉把手，不放标题也不放按钮。
 */
@Composable
private fun PlayerHeader(compact: Boolean, dismissSwipe: Modifier) {
    Column(
        Modifier.fillMaxWidth().then(dismissSwipe).padding(top = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.30f)),
        )
    }
}
