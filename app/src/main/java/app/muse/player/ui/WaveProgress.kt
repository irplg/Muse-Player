package app.muse.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Draw-only moving sine wave; phases loop seamlessly and do not recompose the player. */
@Composable
internal fun WaveProgress(progress: Float, moving: Boolean, reducedMotion: Boolean) {
    val phase = if (moving && !reducedMotion) {
        rememberInfiniteTransition(label = "seekWave").animateFloat(
            initialValue = 0f, targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
            label = "seekWavePhase",
        )
    } else null
    val wave = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val half = 1.5.dp.toPx()
        val width = (size.width - half * 2f).coerceAtLeast(0f)
        if (width <= 0f) return@Canvas
        val y = center.y
        val played = width * progress.coerceIn(0f, 1f)
        // Only draw the unplayed tail; never put a straight baseline under the wave.
        val tailStart = if (played > 0f) (played + 5.dp.toPx()).coerceAtMost(width) else 0f
        if (tailStart < width) {
            drawLine(Color.White.copy(alpha = .25f), Offset(half + tailStart, y), Offset(half + width, y), half * 2f, StrokeCap.Round)
        }
        if (played <= 0f) return@Canvas
        val amplitude = if (phase == null) 0f else 2.5.dp.toPx()
        val wavelength = 28.dp.toPx()
        val angle = phase?.value ?: 0f
        val segments = ceil(played / 2.dp.toPx()).toInt().coerceIn(1, 512)
        wave.rewind()
        for (i in 0..segments) {
            val x = played * i / segments
            val envelope = (minOf(x, played - x) / (wavelength / 3)).coerceIn(0f, 1f)
            val offset = amplitude * envelope * sin(x / wavelength * (2 * PI).toFloat() - angle)
            if (i == 0) wave.moveTo(half + x, y + offset) else wave.lineTo(half + x, y + offset)
        }
        drawPath(wave, Color.White, style = Stroke(width = half * 2f, cap = StrokeCap.Round))
    }
}
