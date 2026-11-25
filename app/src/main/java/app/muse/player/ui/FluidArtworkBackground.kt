package app.muse.player.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * AMLL's PixiRenderer uses two opposing Pixi BulgePinchFilter passes over album art.
 * Here we port the filter's displacement to geometry (instead of two full-screen FBOs).
 * See assets/licenses/muse-art-fluid.txt for pinned sources, changes and license.
 */
@Composable
internal fun FluidArtworkBackground(texture: Bitmap, moving: Boolean, strength: Float = 1f) {
    var phase by remember { mutableFloatStateOf(0f) }
    val animate = moving && Build.VERSION.SDK_INT >= 29
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            delay(32)
            withFrameNanos { now ->
                // Preserve the last frame on pause; don't fast-forward after a hidden window.
                phase = (phase + ((now - last) / 1e9f).coerceIn(0f, .12f) * .24f) % (2f * PI.toFloat())
                last = now
            }
        }
    }
    val steps = if (Build.VERSION.SDK_INT >= 29) 24 else 12
    val vertices = remember(steps) { FloatArray((steps + 1) * (steps + 1) * 2) }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG) }
    val scrim = remember { Brush.verticalGradient(listOf(Color(0x4D0D101D), Color(0x6B090C14), Color(0xCC060911))) }
    Canvas(Modifier.fillMaxSize()) {
        val t = phase
        val cx = .36f + .20f * cos(t); val cy = .25f + .16f * sin(2f * t)
        val dx = .65f + .18f * sin(t); val dy = .72f + .13f * cos(2f * t)
        val aspect = (size.width / size.height.coerceAtLeast(1f)).coerceIn(.55f, 1.8f)
        var k = 0
        for (row in 0..steps) for (col in 0..steps) {
            val u = col.toFloat() / steps; val v = row.toFloat() / steps
            val a = bulgeScale((u - cx) * aspect, v - cy, .68f, .94f * strength)
            val x = cx + (u - cx) * a; val y = cy + (v - cy) * a
            val b = bulgeScale((x - dx) * aspect, y - dy, .69f, .83f * strength)
            // Lock the perimeter; no moving gaps exposing the screen behind the mesh.
            val envelope = 16f * u * (1f - u) * v * (1f - v)
            vertices[k++] = (u + (dx + (x - dx) * b - u) * envelope) * size.width
            vertices[k++] = (v + (dy + (y - dy) * b - v) * envelope) * size.height
        }
        drawIntoCanvas { it.nativeCanvas.drawBitmapMesh(texture, steps, steps, vertices, 0, null, 0, paint) }
        drawRect(scrim)
    }
}

// @pixi/filter-bulge-pinch 5.1.1 (MIT), positive-strength branch. Pixi's
// smoothstep(0, radius/distance, distance/radius) reduces to smoothstep((d/r)^2).
private fun bulgeScale(x: Float, y: Float, radius: Float, strength: Float): Float {
    val q = ((x * x + y * y) / (radius * radius)).coerceIn(0f, 1f)
    return 1f + (q * q * (3f - 2f * q) - 1f) * strength * .75f
}
