package app.muse.player.player

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * 空间音频开关的唯一入口。
 *
 * 两条通路：
 * 1. 系统空间音频（Android 12L / API 32+ 且设备声明了 Spatializer，手机端通常还要求系统里
 *    打开空间音频并使用支持的耳机）：只把音轨标记为「可空间化」，交给系统做双耳渲染。
 * 2. 软件立体声扩展（兜底，所有机型/耳机都能听到差别）：交叉馈送 + 耳间延时 +
 *    头部遮蔽低通 + 轻微展宽，把声音从「脑袋里」推到「头外面」。
 *
 * 系统正在做空间化时不再叠加软件处理，避免双重渲染。
 */
object SpatialAudio {
    private const val PREFS = "spatial_audio"
    private const val KEY_ON = "on"

    private lateinit var appCtx: Context

    /** 插在播放器音频处理链里的软件空间化处理器。 */
    val processor = SpatialAudioProcessor()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /** 开关变化后由 PlayerHub 重新下发音频属性。 */
    var onChanged: (() -> Unit)? = null

    private fun prefs() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        _enabled.value = prefs().getBoolean(KEY_ON, true)
        apply()
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        prefs().edit().putBoolean(KEY_ON, on).apply()
        apply()
    }

    fun toggle() = setEnabled(!_enabled.value)

    /** Android 12L 起才有 Spatializer；更早的系统（如安卓 10）直接走软件通路。 */
    @RequiresApi(32)
    private fun platformSpatializer(): Spatializer? =
        appCtx.getSystemService(AudioManager::class.java)?.spatializer

    private fun systemAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        val s = platformSpatializer() ?: return false
        return s.isAvailable
    }

    /** 系统空间音频此刻是否真的在工作（用户已在系统设置里打开）。 */
    private fun systemActive(): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        val s = platformSpatializer() ?: return false
        return s.isAvailable && s.isEnabled
    }

    private fun apply() {
        val on = _enabled.value
        val system = on && systemActive()
        // 系统接管时关掉软件处理，避免两次渲染叠加。
        processor.updateEnabled(on && !system)
        _status.value = when {
            !on -> "已关闭"
            system -> "系统空间音频"
            systemAvailable() -> "软件立体声扩展（系统空间音频未开启）"
            else -> "软件立体声扩展"
        }
        onChanged?.invoke()
    }
}

/**
 * 立体声外化处理器：不做多声道上混，只把左右声道往头外推开。
 *
 * 具体做法（全部在 PCM 上逐帧完成，开销很小）：
 * - 中／侧分离后把侧信号放大 [WIDTH]，声场变宽；
 * - 对侧信号延后约 0.28 ms（耳间时间差）后与同侧混合，混合量 [CROSSFEED]；
 * - 交叉的那一路先过 700 Hz 一阶低通，模拟头部对高频的遮蔽；
 * - 末端软限幅，避免展宽后削顶。
 */
class SpatialAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = true

    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var pcm = false
    private var delaySamples = 12
    private var lowPass = 0.1f

    private val lineL = FloatArray(DELAY_MAX)
    private val lineR = FloatArray(DELAY_MAX)
    private var cursor = 0
    private var shadowL = 0f
    private var shadowR = 0f
    private var frameL = 0f
    private var frameR = 0f

    /** 注意不能用 setEnabled 作函数名：会和属性 enabled 的 setter 撞 JVM 签名。 */
    fun updateEnabled(on: Boolean) {
        if (enabled != on) {
            enabled = on
            clearState()
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        pcm = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        delaySamples = (rate * INTERAURAL_DELAY_SEC).toInt().coerceIn(1, DELAY_MAX - 1)
        lowPass = (1.0 - exp(-2.0 * PI * SHADOW_CUTOFF_HZ / rate)).toFloat().coerceIn(0.02f, 1f)
        clearState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        out.order(ByteOrder.nativeOrder())
        val frameBytes = channels * bytesPerSample()
        if (!enabled || !pcm || channels != 2 || size < frameBytes) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val frames = size / frameBytes
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, out, frames)
            else -> process16(inputBuffer, out, frames)
        }
        // asShortBuffer/asFloatBuffer 是独立视图，不会推进原 buffer 的 position，这里手动对齐。
        inputBuffer.position(inputBuffer.position() + frames * frameBytes)
        // 末尾不足一帧的字节原样带过去。
        if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        clearState()
    }

    private fun bytesPerSample(): Int = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

    private fun clearState() {
        lineL.fill(0f)
        lineR.fill(0f)
        cursor = 0
        shadowL = 0f
        shadowR = 0f
    }

    private fun process16(input: ByteBuffer, out: ByteBuffer, frames: Int) {
        val src = input.order(ByteOrder.nativeOrder()).asShortBuffer()
        val dst = out.asShortBuffer()
        for (i in 0 until frames) {
            render(src.get() / 32768f, src.get() / 32768f)
            dst.put((softClip(frameL) * 32767f).toInt().toShort())
            dst.put((softClip(frameR) * 32767f).toInt().toShort())
        }
        out.position(out.position() + frames * 4)
    }

    private fun processFloat(input: ByteBuffer, out: ByteBuffer, frames: Int) {
        val src = input.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val dst = out.asFloatBuffer()
        for (i in 0 until frames) {
            render(src.get(), src.get())
            dst.put(softClip(frameL))
            dst.put(softClip(frameR))
        }
        out.position(out.position() + frames * 8)
    }

    /** 单帧处理，结果写进 [frameL] / [frameR]（避免每帧分配对象）。 */
    private fun render(l: Float, r: Float) {
        lineL[cursor] = l
        lineR[cursor] = r
        var read = cursor - delaySamples
        if (read < 0) read += DELAY_MAX
        val delayedL = lineL[read]
        val delayedR = lineR[read]
        cursor = if (cursor + 1 >= DELAY_MAX) 0 else cursor + 1

        // 头部遮蔽：对侧声音的高频被挡掉一部分。
        shadowL += lowPass * (delayedL - shadowL)
        shadowR += lowPass * (delayedR - shadowR)

        val mid = (l + r) * 0.5f
        val side = (l - r) * 0.5f * WIDTH
        frameL = mid + side + shadowR * CROSSFEED
        frameR = mid - side + shadowL * CROSSFEED
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.82f) return x
        val s = if (x < 0f) -1f else 1f
        return s * (0.82f + 0.18f * tanh((a - 0.82f) / 0.18f))
    }

    private companion object {
        const val DELAY_MAX = 256
        const val INTERAURAL_DELAY_SEC = 0.00028f
        const val SHADOW_CUTOFF_HZ = 700.0
        const val WIDTH = 1.12f
        const val CROSSFEED = 0.30f
    }
}
