package app.muse.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Use the same art as the feature card, blurred off the UI thread even on Android 8–11. */
@Composable
internal fun DiscoveryBackdrop(cover: String?, modifier: Modifier = Modifier, fadeToSurface: Boolean = true) {
    val surface = MaterialTheme.colorScheme.surface
    val blurred = key(cover) {
        val bitmap by produceState<Bitmap?>(null) {
            if (cover != null) {
                val source = ImageLoaderLite.load(cover, 256)
                value = withContext(Dispatchers.Default) { source?.let(::blurDiscoveryArt) }
            }
        }
        bitmap
    }
    Box(modifier) {
        blurred?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (fadeToSurface) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to surface.copy(alpha = .52f), .60f to surface.copy(alpha = .36f), 1f to surface,
            )))
        }
    }
}

private fun blurDiscoveryArt(source: Bitmap): Bitmap? {
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) ?: return null else source
    val size = 64
    val pixels = IntArray(size * size)
    Bitmap.createScaledBitmap(bitmap, size, size, true).getPixels(pixels, 0, size, 0, 0, size, size)
    val buffer = IntArray(pixels.size)
    repeat(2) {
        averageArt(pixels, buffer, size, horizontal = true)
        averageArt(buffer, pixels, size, horizontal = false)
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private fun averageArt(input: IntArray, output: IntArray, size: Int, horizontal: Boolean) {
    val radius = 5
    for (y in 0 until size) for (x in 0 until size) {
        var r = 0; var g = 0; var b = 0
        for (d in -radius..radius) {
            val px = if (horizontal) (x + d).coerceIn(0, size - 1) else x
            val py = if (horizontal) y else (y + d).coerceIn(0, size - 1)
            val c = input[py * size + px]
            r += c ushr 16 and 255; g += c ushr 8 and 255; b += c and 255
        }
        val n = radius * 2 + 1
        output[y * size + x] = (255 shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
    }
}
