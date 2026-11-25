package app.muse.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

object LocalLibrary {
    @Volatile var contentResolver: android.content.ContentResolver? = null
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val loading = MutableStateFlow(false)

    @Suppress("DEPRECATION")
    suspend fun refresh(ctx: Context) {
        contentResolver = ctx.contentResolver
        loading.value = true
        tracks.value = withContext(Dispatchers.IO) {
            val out = ArrayList<Track>()
            val collection = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA,
            )
            val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
            runCatching {
                ctx.contentResolver.query(collection, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE LOCALIZED ASC")
                    ?.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        while (c.moveToNext()) {
                            val id = c.getLong(iId)
                            val uri = ContentUris.withAppendedId(collection, id)
                            val art = if (Build.VERSION.SDK_INT >= 29) {
                                uri.toString()
                            } else {
                                ContentUris.withAppendedId(
                                    Uri.parse("content://media/external/audio/albumart"),
                                    c.getLong(iAlbumId),
                                ).toString()
                            }
                            val artist = c.getString(iArtist)
                            out.add(
                                Track(
                                    id = "local:$id",
                                    title = c.getString(iTitle) ?: "未知歌曲",
                                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") "未知艺人" else artist,
                                    album = c.getString(iAlbum).orEmpty(),
                                    durationMs = c.getLong(iDur),
                                    artwork = art,
                                    localUri = uri.toString(),
                                    dataPath = c.getString(iData),
                                ),
                            )
                        }
                    }
            }
            out
        }
        loading.value = false
    }
}
