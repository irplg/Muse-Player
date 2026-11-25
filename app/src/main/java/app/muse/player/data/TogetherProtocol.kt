package app.muse.player.data

import org.json.JSONArray
import org.json.JSONObject

/** Parsed listen-together playback command/state. No credentials or raw responses are retained. */
data class TogetherPlaybackState(
    val type: String,
    val songId: Long?,
    val progressMs: Long?,
    val status: String?,
    val operatorId: Long?,
    val clientSeq: Int?,
    val serverSeq: Long?,
)

object TogetherProtocol {
    private val types = setOf("PLAY", "PAUSE", "GOTO", "SEEK")

    /** Finds commandInfo (which the API returns as a JSON string) at any response depth. */
    fun find(root: JSONObject, allowStateFields: Boolean = false): TogetherPlaybackState? =
        findAny(root, 0, allowStateFields)

    private fun findAny(node: Any?, depth: Int, allowState: Boolean): TogetherPlaybackState? {
        if (depth > 8) return null
        when (node) {
            is JSONObject -> {
                parseObject(node, allowState)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val v = node.opt(keys.next())
                    findAny(v, depth + 1, allowState)?.let { return it }
                    if (v is String && v.trimStart().startsWith("{")) {
                        runCatching { JSONObject(v) }.getOrNull()?.let {
                            findAny(it, depth + 1, allowState)?.let { found -> return found }
                        }
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findAny(node.opt(i), depth + 1, allowState)?.let { return it }
            }
        }
        return null
    }

    private fun parseObject(o: JSONObject, allowState: Boolean): TogetherPlaybackState? {
        val rawType = o.optString("commandType").uppercase()
        val type = if (rawType in types) rawType else if (allowState && (o.has("songId") || o.has("targetSongId"))) {
            when (o.optString("playStatus").uppercase()) {
                "PLAY" -> "PLAY"
                "PAUSE" -> "PAUSE"
                else -> return null
            }
        } else return null
        val song = listOf("targetSongId", "songId", "trackId").asSequence()
            .mapNotNull { o.optString(it).toLongOrNull() ?: o.optLong(it, 0L).takeIf { id -> id > 0 } }
            .firstOrNull()
        val progress = listOf("progress", "playedTime", "position").asSequence()
            .mapNotNull { key -> if (o.has(key)) o.optLong(key).coerceAtLeast(0L) else null }
            .firstOrNull()
        val status = o.optString("playStatus").uppercase().takeIf { it == "PLAY" || it == "PAUSE" }
            ?: type.takeIf { it == "PLAY" || it == "PAUSE" }
        return TogetherPlaybackState(
            type, song, progress, status,
            o.optLong("userId", o.optLong("operatorId", -1L)).takeIf { it >= 0 },
            o.optInt("clientSeq", -1).takeIf { it >= 0 },
            o.optLong("serverSeq", o.optLong("version", -1L)).takeIf { it >= 0 },
        )
    }
}
