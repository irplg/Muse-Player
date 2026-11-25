package app.muse.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

object LyricParser {
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val yrcHead = Regex("^\\[(\\d+),(\\d+)]")
    private val yrcWord = Regex("\\((\\d+),(\\d+),(-?\\d+)\\)")

    /** 标准 LRC：[mm:ss.xx]歌词，支持一行多个时间戳 */
    fun parseLrc(text: String): List<LyricLine> {
        val rows = ArrayList<Pair<Long, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val content = line.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                rows.add(((min * 60 + sec) * 1000 + ms) to content)
            }
        }
        rows.sortBy { it.first }
        return rows.mapIndexed { i, r ->
            LyricLine(r.first, rows.getOrNull(i + 1)?.first ?: (r.first + 5000), r.second)
        }.filter { it.text.isNotBlank() }
    }

    /** 网易云逐字歌词：[行开始,行时长](字开始,字时长,0)字 ... */
    fun parseYrc(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val head = yrcHead.find(line) ?: continue
            val start = head.groupValues[1].toLong()
            val dur = head.groupValues[2].toLong()
            val rest = line.substring(head.range.last + 1)
            val marks = yrcWord.findAll(rest).toList()
            if (marks.isEmpty()) continue
            val words = ArrayList<LyricWord>()
            for ((i, m) in marks.withIndex()) {
                val from = m.range.last + 1
                val to = if (i + 1 < marks.size) marks[i + 1].range.first else rest.length
                val ws = m.groupValues[1].toLong()
                val wd = m.groupValues[2].toLong()
                words.add(LyricWord(ws, ws + wd, rest.substring(from, to)))
            }
            // 兼容“相对行首”的时间写法
            if (words.first().start < start - 500) {
                for (k in words.indices) words[k] = words[k].copy(start = words[k].start + start, end = words[k].end + start)
            }
            words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
            val full = words.joinToString("") { it.text }
            if (full.isBlank()) continue
            out.add(LyricLine(start, start + dur, full, words))
        }
        out.sortBy { it.start }
        return out
    }

    fun attachTranslation(lines: List<LyricLine>, tr: List<LyricLine>): List<LyricLine> {
        if (tr.isEmpty()) return lines
        return lines.map { l ->
            val m = tr.minByOrNull { abs(it.start - l.start) }
            if (m != null && abs(m.start - l.start) < 800 && m.text.isNotBlank()) l.copy(translation = m.text) else l
        }
    }
}

object LyricRepo {
    private val cache = HashMap<String, List<LyricLine>>()

    suspend fun forTrack(t: Track): List<LyricLine> {
        cache[t.id]?.takeIf { it.isNotEmpty() }?.let { return it }
        var lines: List<LyricLine> = emptyList()
        var neteaseId = t.neteaseId
        // Local embedded tags are authoritative and are attempted before sidecars/network.
        if (t.localUri != null) {
            lines = runCatching { LocalLibrary.contentResolver?.let { EmbeddedLyrics.read(it, android.net.Uri.parse(t.localUri)) } ?: emptyList() }.getOrDefault(emptyList())
        }
        if (lines.isEmpty()) {
            lines = sidecar(t)
            if (lines.isEmpty()) neteaseId = neteaseId ?: runCatching { Netease.matchId(t.title, t.artist) }.getOrNull()
        }
        val remoteId = neteaseId
        if (lines.isEmpty() && remoteId != null) {
            lines = runCatching { build(Netease.lyric(remoteId)) }
                .onFailure { Netease.log("lyric 失败: ${it.message}") }
                .getOrDefault(emptyList())
        }
        cache[t.id] = lines
        return lines
    }

    private fun build(raw: Netease.LyricRaw): List<LyricLine> {
        val base = if (raw.yrc.isNotBlank()) {
            LyricParser.parseYrc(raw.yrc).ifEmpty { LyricParser.parseLrc(raw.lrc) }
        } else {
            LyricParser.parseLrc(raw.lrc)
        }
        if (base.size == 1 && base[0].text.contains("纯音乐")) return emptyList()
        return if (raw.tlrc.isNotBlank()) LyricParser.attachTranslation(base, LyricParser.parseLrc(raw.tlrc)) else base
    }

    /** 本地歌曲同目录同名 .lrc（Android 11+ 多数情况读不到，读不到就静默跳过） */
    private suspend fun sidecar(t: Track): List<LyricLine> = withContext(Dispatchers.IO) {
        val path = t.dataPath ?: return@withContext emptyList()
        runCatching {
            val f = File(path)
            val lrc = File(f.parentFile, f.nameWithoutExtension + ".lrc")
            if (lrc.exists() && lrc.canRead() && lrc.length() <= 4L * 1024 * 1024) {
                lrc.inputStream().use { LyricParser.parseLrc(it.readBytes().toString(Charsets.UTF_8)) }
            } else emptyList()
        }.getOrDefault(emptyList())
    }
}
