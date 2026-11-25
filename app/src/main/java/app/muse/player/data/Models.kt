package app.muse.player.data

/** 一首歌。本地歌曲有 localUri，网易云歌曲有 neteaseId（播放时再解析真实地址）。 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artwork: String? = null,
    val localUri: String? = null,
    val neteaseId: Long? = null,
    val dataPath: String? = null,
)

data class ArtistInfo(val id: Long, val name: String, val artwork: String?)

data class AlbumInfo(val id: Long, val name: String, val artwork: String?, val artist: String = "")

enum class MusicSearchCategory { Songs, Artists, Albums }

data class MusicSearchResults(
    val tracks: List<Track>,
    val artists: List<ArtistInfo>,
    val albums: List<AlbumInfo>,
    val unavailable: Set<MusicSearchCategory> = emptySet(),
    /** Unenriched artist reference from the first song's first artist, never from artist search. */
    val firstTrackArtist: ArtistInfo? = null,
)

data class ArtistDetail(val artist: ArtistInfo, val description: String, val tracks: List<Track>)

data class AlbumDetail(val album: AlbumInfo, val description: String, val tracks: List<Track>)

data class LyricWord(val start: Long, val end: Long, val text: String)

data class LyricLine(
    val start: Long,
    val end: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
)

data class PlaylistInfo(val id: Long, val name: String, val cover: String?, val count: Int)

data class Profile(val userId: Long, val nickname: String, val avatar: String?)

data class RoomUser(val userId: Long, val nickname: String, val avatar: String?)
