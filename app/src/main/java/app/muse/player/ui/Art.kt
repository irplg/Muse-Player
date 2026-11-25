package app.muse.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.graphics.Color as Pixel
import kotlin.math.cos
import app.muse.player.MuseApp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ImageLoaderLite {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(64 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val http = OkHttpClient()

    fun peek(model: String, px: Int): Bitmap? = cache.get("$model@$px")

    suspend fun load(model: String, px: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$model@$px"
        cache.get(key)?.let { return@withContext it }
        val bmp = try {
            when {
                model.startsWith("http") -> {
                    val url = if (model.contains("music.126.net") && !model.contains("?")) "$model?param=${px}y$px" else model
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                    }
                }
                model.startsWith("content://") -> loadContent(Uri.parse(model), px)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp)
        bmp
    }

    private fun loadContent(uri: Uri, px: Int): Bitmap? {
        val cr = MuseApp.instance.contentResolver
        return if (Build.VERSION.SDK_INT >= 29 && uri.toString().contains("/audio/media/")) {
            cr.loadThumbnail(uri, Size(px, px), null)
        } else {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }
}

@Composable
fun Art(
    model: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    px: Int = 400,
) {
    val bmp by produceState<Bitmap?>(
        model?.let { ImageLoaderLite.peek(it, px) },
        model,
        px,
    ) {
        value = if (model == null) null else ImageLoaderLite.load(model, px)
    }
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Glyph(
                Glyphs.Note,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                size = 28.dp,
            )
        }
    }
}

/** Preserve the spatial arrangement of the sleeve, not just its two most frequent colors. */
@Composable
internal fun rememberArtTexture(model: String?): Bitmap {
    val texture by produceState(fallbackTexture, model) {
        value = withContext(Dispatchers.Default) {
            val image = model?.let { ImageLoaderLite.load(it, 200) } ?: return@withContext fallbackTexture
            // Canvas accepts hardware thumbnails on screen but CPU pixels require a software bitmap.
            val software = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
            if (software == null) return@withContext fallbackTexture
            val small = Bitmap.createScaledBitmap(software, 32, 32, true)
            val samples = IntArray(32 * 32)
            small.getPixels(samples, 0, 32, 0, 0, 32, 32)
            val tiles = IntArray(16) { tile ->
                var r = 0; var g = 0; var b = 0
                for (y in 0..7) for (x in 0..7) {
                    val c = samples[(tile / 4 * 8 + y) * 32 + tile % 4 * 8 + x]
                    r += Pixel.red(c); g += Pixel.green(c); b += Pixel.blue(c)
                }
                Pixel.rgb(r / 64, g / 64, b / 64)
            }
            smoothTexture(tiles)
        }
    }
    return texture
}

private val fallbackTexture by lazy {
    smoothTexture(intArrayOf(0x314854, 0x525871, 0x414B62, 0x33434B,
        0x515670, 0x76596B, 0x4D4A69, 0x2A3E4C, 0x675468, 0x474760,
        0x2E4555, 0x35515C, 0x413F56, 0x2D3F4F, 0x38535D, 0x494759))
}

// Cosine interpolation gives the low-pass artwork a soft slope across tile boundaries.
private fun smoothTexture(tiles: IntArray): Bitmap {
    val n = 64
    val weights = FloatArray(n) { i ->
        val f = i * 3f / (n - 1) % 1f
        ((1 - cos(Math.PI * f)) * .5).toFloat()
    }
    val pixels = IntArray(n * n) { index ->
        val x = index % n; val y = index / n
        val i = x * 3 / (n - 1); val j = y * 3 / (n - 1)
        val i1 = (i + 1).coerceAtMost(3); val j1 = (j + 1).coerceAtMost(3)
        val a = tiles[j * 4 + i]; val b = tiles[j * 4 + i1]
        val c = tiles[j1 * 4 + i]; val d = tiles[j1 * 4 + i1]
        fun channel(shift: Int): Int {
            val top = ((a shr shift) and 255) * (1 - weights[x]) + ((b shr shift) and 255) * weights[x]
            val bot = ((c shr shift) and 255) * (1 - weights[x]) + ((d shr shift) and 255) * weights[x]
            return (top + (bot - top) * weights[y]).toInt().coerceIn(0, 255)
        }
        Pixel.rgb(channel(16), channel(8), channel(0))
    }
    return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
}

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        px[y * size + x] = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, size, 0, 0, size, size)
    return bmp
}
