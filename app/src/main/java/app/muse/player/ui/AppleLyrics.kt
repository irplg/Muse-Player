package app.muse.player.ui

import android.icu.text.BreakIterator
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muse.player.data.LyricLine
import app.muse.player.player.PlayerHub
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

// 换行时的“波浪”时间轴：整体 WAVE_LEN 秒，每行动画 WAVE_DUR，后面每行再晚 WAVE_STEP
private const val WAVE_LEN = 1.1f
private const val WAVE_DUR = 0.55f
private const val WAVE_STEP = 0.04f

private fun easeOutBack(x: Float): Float {
    val c1 = 1.1f
    val c3 = c1 + 1f
    val u = x - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/** 当前应高亮的行（二分查找，提前 120ms 切换让动画更跟手） */
fun lyricActiveIndex(lines: List<LyricLine>, t: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].start <= t + 120) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/** 唱词时段内连续推进，拖动播放位置时也直接按时码计算。 */
internal fun lyricWordProgress(position: Long, start: Long, end: Long): Float = when {
    position < start -> 0f
    position >= end -> 1f
    else -> ((position - start).toDouble() / (end - start)).toFloat()
}

/**
 * 逐字上浮：每个字的升起时长直接等于它自己的演唱时长乘以系数。
 * 唱得久的字抬得慢、幅度铺满整段时间，一掠而过的字就快速升起，节奏因此跟唱腔一致。
 * 字升起后一直保持，等整行唱完再统一落回，行内不会出现「升-落-再升」的重影。
 */
internal fun lyricWordLift(
    position: Long,
    start: Long,
    end: Long,
    lineEnd: Long,
    wordSize: Int,
    lineSize: Int,
): Float {
    if (position <= start || end <= start) return 0f
    val duration = (end - start).toFloat()
    val rise = (duration * .62f).coerceIn(60f, 900f)
    val up = ((position - start) / rise).coerceIn(0f, 1f)
    val eased = up * up * (3f - 2f * up)
    if (position <= lineEnd) return eased
    val fall = ((position - lineEnd) / 200f).coerceIn(0f, 1f)
    return eased * (1f - fall * fall)
}

/** 每帧读取播放器位置，让逐字高亮是 60fps 平滑的，而不是 250ms 一跳。 */
@Composable
fun rememberSmoothPosition(reducedMotion: Boolean = false): State<Long> {
    val s = remember { mutableLongStateOf(PlayerHub.exo.currentPosition) }
    LaunchedEffect(reducedMotion) {
        while (true) {
            if (reducedMotion) delay(100L) else androidx.compose.runtime.withFrameNanos { }
            val now = PlayerHub.exo.currentPosition
            if (s.longValue != now) s.longValue = now
        }
    }
    return s
}

@Composable
fun AppleLyrics(lines: List<LyricLine>, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val listState = rememberLazyListState()
    val pos = rememberSmoothPosition(reducedMotion)
    val density = LocalDensity.current
    val active by remember(lines) { derivedStateOf { lyricActiveIndex(lines, pos.value) } }

    var browsing by remember { mutableStateOf(false) } // 用户正在手动翻歌词
    var lastTouch by remember { mutableLongStateOf(0L) }
    val flags = remember { booleanArrayOf(false) } // flags[0]：程序自己在滚动
    val wave = remember { Animatable(WAVE_LEN) }
    var waveDelta by remember { mutableFloatStateOf(0f) }
    var waveFrom by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier) {
        val topPad = maxHeight * 0.26f
        val anchorPx = with(density) { topPad.toPx() }

        // 用户手动滚动 -> 进入“浏览”模式（取消模糊），停手 3 秒后回到跟随
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (scrolling && !flags[0]) {
                    browsing = true
                    lastTouch = SystemClock.uptimeMillis()
                }
            }
        }
        LaunchedEffect(browsing) {
            if (browsing) {
                while (true) {
                    delay(400)
                    if (listState.isScrollInProgress) {
                        lastTouch = SystemClock.uptimeMillis()
                    } else if (SystemClock.uptimeMillis() - lastTouch > 3000) {
                        browsing = false
                        break
                    }
                }
            }
        }

        // 换行：瞬间滚到位 + 每一行从旧位置按序“弹”回来 = Apple Music 的波浪错位感
        LaunchedEffect(active, lines, browsing, reducedMotion) {
            if (active < 0 || browsing) return@LaunchedEffect
            val index = active + 1 // 第 0 项是顶部占位
            flags[0] = true
            try {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info != null) {
                    val delta = info.offset - anchorPx
                    waveDelta = delta
                    waveFrom = active
                    listState.scrollBy(delta)
                    if (!reducedMotion) {
                        wave.snapTo(0f)
                        wave.animateTo(WAVE_LEN, tween((WAVE_LEN * 1000).toInt(), easing = LinearEasing))
                    }
                } else if (reducedMotion) {
                    listState.scrollToItem(index, -anchorPx.toInt())
                } else {
                    listState.animateScrollToItem(index, -anchorPx.toInt())
                }
            } finally {
                flags[0] = false
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "top") { Spacer(Modifier.height(topPad)) }
            items(lines.size, key = { it }) { i ->
                LyricItem(
                    i = i,
                    line = lines[i],
                    active = active,
                    browsing = browsing,
                    reducedMotion = reducedMotion,
                    pos = pos,
                    waveT = { wave.value },
                    waveDelta = { waveDelta },
                    waveFrom = { waveFrom },
                    onTap = {
                        browsing = false
                        PlayerHub.seekTo(lines[i].start)
                    },
                )
            }
            item(key = "bottom") { Spacer(Modifier.height(maxHeight * 0.7f)) }
        }
    }
}

@Composable
private fun LyricItem(
    i: Int,
    line: LyricLine,
    active: Int,
    browsing: Boolean,
    reducedMotion: Boolean,
    pos: State<Long>,
    waveT: () -> Float,
    waveDelta: () -> Float,
    waveFrom: () -> Int,
    onTap: () -> Unit,
) {
    val dist = abs(i - active)
    val isActive = i == active
    val targetAlpha = when {
        browsing -> 0.78f
        isActive -> 1f
        else -> (0.46f - 0.07f * min(dist, 4)).coerceAtLeast(0.2f)
    }
    val lineAlpha by animateFloatAsState(targetAlpha, tween(if (reducedMotion) 0 else 380), label = "lyricAlpha")
    val lineScale by animateFloatAsState(
        if (isActive || browsing) 1f else 0.9f,
        if (reducedMotion) tween(0) else spring(dampingRatio = 0.75f, stiffness = 220f),
        label = "lyricScale",
    )
    val blurDp by animateDpAsState(
        if (reducedMotion || browsing || isActive || active < 0) 0.dp else (dist * 1.2f).coerceAtMost(4f).dp,
        tween(if (reducedMotion) 0 else 420),
        label = "lyricBlur",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val from = waveFrom()
                val rank = if (i >= from) min(i - from, 8) else 0
                val local = ((waveT() - rank * WAVE_STEP) / WAVE_DUR).coerceIn(0f, 1f)
                translationY = if (reducedMotion) 0f else waveDelta() * (1f - easeOutBack(local))
                scaleX = lineScale
                scaleY = lineScale
                transformOrigin = TransformOrigin(0f, 0.5f)
                this.alpha = lineAlpha
            }
            .then(if (blurDp > 0.2.dp) Modifier.blur(blurDp, BlurredEdgeTreatment.Unbounded) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .padding(horizontal = 28.dp, vertical = 11.dp),
    ) {
        LyricText(line, isActive, pos, reducedMotion)
        line.translation?.let {
            Text(
                it,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val lyricStyle = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
private val unsungColor = Color.White.copy(alpha = 0.38f)

@Composable
private fun LyricText(line: LyricLine, active: Boolean, pos: State<Long>, reducedMotion: Boolean) {
    // 输入词必须能准确映射到原文。已解析的 YRC 满足此条件；其他输入宁可按普通歌词显示。
    val timed = active && remember(line) {
        line.words.isNotEmpty() && line.words.joinToString("") { it.text } == line.text
    }
    if (!timed) {
        Text(
            line.text,
            style = if (active && !reducedMotion) {
                lyricStyle.copy(shadow = Shadow(Color.White.copy(alpha = 0.56f), Offset.Zero, 17f))
            } else lyricStyle,
            color = Color.White,
        )
        return
    }

    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        line.text,
        style = lyricStyle,
        color = unsungColor,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithCache {
            val lay = layout
            if (lay == null) {
                onDrawWithContent { drawContent() }
            } else {
                // 整段排版只栅格化一次；每帧只移动词和着色，不反复重画整行 glyph。
                val words = lay.wordRuns(line)
                val lineSize = words.sumOf { it.glyphCount }
                val ink = ImageBitmap(lay.size.width.coerceAtLeast(1), lay.size.height.coerceAtLeast(1))
                CanvasDrawScope().draw(this, layoutDirection, Canvas(ink), Size(lay.size.width.toFloat(), lay.size.height.toFloat())) {
                    drawText(lay, Color.White)
                }
                val maxLift = 2.5.dp.toPx()
                val feather = 12.dp.toPx()
                val glowRadius = 5.dp.toPx()
                val glowPadding = ceil(glowRadius * 2 + maxLift).toInt()
                val glowLayer = if (reducedMotion) null else obtainGraphicsLayer()
                val blur = BlurEffect(glowRadius, glowRadius, TileMode.Decal)
                val hasBlur = glowLayer != null && blur.isSupported()
                if (hasBlur) glowLayer?.renderEffect = blur
                val glowSize = IntSize(lay.size.width + glowPadding * 2, lay.size.height + glowPadding * 2)
                val originals = Path()
                val restingHighlight = Path()
                val lifts = FloatArray(words.size)
                val progresses = FloatArray(words.size)
                val layerPaint = Paint()
                onDrawWithContent {
                    val now = pos.value
                    originals.rewind()
                    restingHighlight.rewind()
                    var hasGlow = false
                    for ((i, word) in words.withIndex()) {
                        val progress = lyricWordProgress(now, word.start, word.end)
                        val lift = if (reducedMotion) 0f else lyricWordLift(now, word.start, word.end, line.end, word.glyphCount, lineSize)
                        progresses[i] = progress
                        lifts[i] = lift
                        if (lift > 0f || progress > 0f) originals.addPath(word.mask)
                        if (lift == 0f && progress == 1f) restingHighlight.addPath(word.mask)
                        hasGlow = hasGlow || (lift > 0f && progress > 0f)
                    }
                    // 挖掉需要重新着色或移动的原字，避免暗底与发亮字重叠。
                    if (originals.isEmpty) drawContent()
                    else clipPath(originals, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                    if (hasGlow && glowLayer != null) {
                        glowLayer.record(size = glowSize) {
                            translate(glowPadding.toFloat(), glowPadding.toFloat()) {
                                for ((i, word) in words.withIndex()) {
                                    val lift = lifts[i]
                                    val progress = progresses[i]
                                    if (lift <= 0f || progress <= 0f) continue
                                    translate(top = -maxLift * lift) {
                                        paintLyricWord(ink, word, progress, feather, 0f, lift, layerPaint)
                                    }
                                }
                            }
                        }
                        translate(-glowPadding.toFloat(), -glowPadding.toFloat()) {
                            if (hasBlur) {
                                glowLayer.alpha = 0.8f
                                drawLayer(glowLayer)
                            } else {
                                // Android 8–11 的 RenderEffect 替代方案。
                                glowLayer.alpha = 0.1f
                                for (offset in fallbackGlowOffsets) {
                                    translate(offset.x * glowRadius, offset.y * glowRadius) { drawLayer(glowLayer) }
                                }
                            }
                        }
                    }
                    if (!restingHighlight.isEmpty) clipPath(restingHighlight) { drawImage(ink) }
                    for ((i, word) in words.withIndex()) {
                        val lift = lifts[i]
                        val progress = progresses[i]
                        if (lift == 0f && (progress == 0f || progress == 1f)) continue
                        translate(top = -maxLift * lift) {
                            paintLyricWord(ink, word, progress, feather, unsungColor.alpha, 1f, layerPaint)
                        }
                    }
                }
            }
        },
    )
}

private val fallbackGlowOffsets = listOf(
    Offset(-0.33f, 0f), Offset(0.33f, 0f), Offset(0f, -0.33f), Offset(0f, 0.33f),
    Offset(-0.2f, -0.2f), Offset(-0.2f, 0.2f), Offset(0.2f, -0.2f), Offset(0.2f, 0.2f),
)

private data class LyricRun(val rect: Rect, val rtl: Boolean) {
    val width: Float get() = rect.width
}

private data class LyricWordRuns(
    val start: Long,
    val end: Long,
    val glyphCount: Int,
    val runs: List<LyricRun>,
    val mask: Path,
    val width: Float,
)

private fun DrawScope.paintLyricWord(
    ink: ImageBitmap,
    word: LyricWordRuns,
    progress: Float,
    feather: Float,
    dim: Float,
    bright: Float,
    layerPaint: Paint,
) {
    if (progress == 0f || progress == 1f) {
        val alpha = if (progress == 0f) dim else bright
        if (alpha > 0f) clipPath(word.mask) { drawImage(ink, alpha = alpha) }
        return
    }
    var sung = word.width * progress
    for (run in word.runs) {
        val r = run.rect
        val reveal = (sung / run.width).coerceIn(0f, 1f)
        sung -= run.width
        clipRect(r.left, r.top, r.right, r.bottom) {
            if (reveal == 0f || reveal == 1f) {
                val alpha = if (reveal == 0f) dim else bright
                if (alpha > 0f) drawImage(ink, alpha = alpha)
            } else {
                val spread = min(feather, run.width)
                val front = (run.width + spread) * reveal - spread
                val start = if (run.rtl) r.right - front else r.left + front
                val end = if (run.rtl) start - spread else start + spread
                // 渐变一次着色，以字形的抗锯齿 alpha 为遮罩；没有进度裁切线或分级色带。
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(r, layerPaint)
                    try {
                        drawImage(ink)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White.copy(alpha = bright), Color.White.copy(alpha = dim)),
                                startX = start,
                                endX = end,
                            ),
                            topLeft = r.topLeft,
                            size = r.size,
                            blendMode = BlendMode.SrcIn,
                        )
                    } finally {
                        canvas.restore()
                    }
                }
            }
        }
    }
}

private fun TextLayoutResult.wordRuns(line: LyricLine): List<LyricWordRuns> {
    val text = line.text
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    // 整行划分字素，避免外部词时间戳恰好拆开组合字符或 ZWJ 序列。
    var from = breaks.first()
    var to = breaks.next()
    var wordEnd = 0
    return buildList {
        for (word in line.words) {
            wordEnd += word.text.length
            val runs = ArrayList<LyricRun>()
            var count = 0
            while (to != BreakIterator.DONE && from < wordEnd) {
                if (!(from until to).all { text[it].isWhitespace() }) {
                    val row = getLineForOffset(from)
                    val end = min(to, getLineEnd(row, visibleEnd = true))
                    if (end > from) {
                        val r = getPathForRange(from, end).getBounds()
                        if (r.width > 0f && r.height > 0f) {
                            count++
                            val rtl = getBidiRunDirection(from) == ResolvedTextDirection.Rtl
                            val last = runs.lastOrNull()
                            if (last != null && last.rtl == rtl && last.rect.top == r.top && last.rect.bottom == r.bottom &&
                                (if (rtl) r.right <= last.rect.right else r.left >= last.rect.left)
                            ) {
                                runs[runs.lastIndex] = LyricRun(Rect(min(last.rect.left, r.left), r.top, maxOf(last.rect.right, r.right), r.bottom), rtl)
                            } else {
                                runs.add(LyricRun(r, rtl))
                            }
                        }
                    }
                }
                from = to
                to = breaks.next()
            }
            if (runs.isNotEmpty()) {
                val mask = Path().apply { for (run in runs) addRect(run.rect) }
                add(LyricWordRuns(word.start, word.end, count, runs, mask, runs.sumOf { it.width.toDouble() }.toFloat()))
            }
        }
    }
}
