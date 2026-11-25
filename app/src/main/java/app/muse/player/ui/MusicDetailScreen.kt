package app.muse.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.muse.player.data.AlbumInfo
import app.muse.player.data.ArtistInfo
import app.muse.player.data.Netease
import app.muse.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

@Composable
internal fun ArtistDetailScreen(
    artist: ArtistInfo,
    currentTrackId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    onClose: () -> Unit,
) {
    MusicDetailScreen(
        kind = MusicDetailKind.Artist,
        id = artist.id,
        preview = DetailHeader(artist.name, artist.artwork),
        currentTrackId = currentTrackId,
        onPlay = onPlay,
        onClose = onClose,
        load = {
            val detail = Netease.artistDetail(artist.id)
            DetailContent(
                DetailHeader(detail.artist.name, detail.artist.artwork ?: artist.artwork),
                detail.description,
                detail.tracks,
            )
        },
    )
}

@Composable
internal fun AlbumDetailScreen(
    album: AlbumInfo,
    currentTrackId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    onClose: () -> Unit,
) {
    MusicDetailScreen(
        kind = MusicDetailKind.Album,
        id = album.id,
        preview = DetailHeader(album.name, album.artwork, album.artist),
        currentTrackId = currentTrackId,
        onPlay = onPlay,
        onClose = onClose,
        load = {
            val detail = Netease.albumDetail(album.id)
            DetailContent(
                DetailHeader(detail.album.name, detail.album.artwork ?: album.artwork, detail.album.artist.ifBlank { album.artist }),
                detail.description,
                detail.tracks,
            )
        },
    )
}

private enum class MusicDetailKind(val label: String, val trackHeading: String) {
    Artist("歌手", "热门歌曲"), Album("专辑", "专辑曲目"),
}

private data class DetailHeader(val name: String, val artwork: String?, val subtitle: String = "")
private data class DetailContent(val header: DetailHeader, val description: String, val tracks: List<Track>)
private sealed interface DetailState {
    data object Loading : DetailState
    data object Failed : DetailState
    data class Loaded(val content: DetailContent) : DetailState
}

@Composable
private fun MusicDetailScreen(
    kind: MusicDetailKind,
    id: Long,
    preview: DetailHeader,
    currentTrackId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    onClose: () -> Unit,
    load: suspend () -> DetailContent,
) {
    var attempt by remember(kind, id) { mutableIntStateOf(0) }
    var state by remember(kind, id) { mutableStateOf<DetailState>(DetailState.Loading) }
    LaunchedEffect(kind, id, attempt) {
        state = DetailState.Loading
        try {
            val content = load()
            ensureActive()
            state = DetailState.Loaded(content)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ensureActive()
            state = DetailState.Failed
        }
    }
    val displayedState = state
    val content = (displayedState as? DetailState.Loaded)?.content
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "返回搜索" }) {
                    Glyph(Glyphs.Back, tint = colors.onSurface)
                }
                Text("${kind.label}详情", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 210.dp)) {
                item(key = "header") {
                    DetailHero(kind, content?.header ?: preview, content?.tracks.orEmpty(), onPlay)
                }
                when (displayedState) {
                    DetailState.Loading -> item {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "正在加载详情" }, strokeWidth = 2.dp)
                        }
                    }
                    DetailState.Failed -> item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text("未能加载${kind.label}详情", style = MaterialTheme.typography.titleMedium)
                            Text("请检查网络连接后重试。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            TextButton(onClick = { attempt++ }) { Text("重试") }
                        }
                    }
                    is DetailState.Loaded -> {
                        val detail = displayedState.content
                        item(key = "tracks") {
                            Text(kind.trackHeading, Modifier.padding(start = Muse.Page, end = Muse.Page, top = 18.dp, bottom = 6.dp), style = MaterialTheme.typography.headlineSmall)
                        }
                        if (detail.tracks.isEmpty()) item {
                            Text("暂无可显示的歌曲", Modifier.padding(horizontal = Muse.Page, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        itemsIndexed(detail.tracks, key = { _, track -> "track:${track.id}" }) { index, track ->
                            DiscoverySong(track, track.id == currentTrackId) { onPlay(detail.tracks, index) }
                        }
                        if (detail.description.isNotBlank()) item(key = "description") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${kind.label}简介", style = MaterialTheme.typography.headlineSmall)
                                Text(detail.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHero(
    kind: MusicDetailKind,
    header: DetailHeader,
    tracks: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val size = Modifier.size(144.dp)
        if (kind == MusicDetailKind.Artist && header.artwork == null) {
            Box(size.background(colors.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Person, tint = colors.onSurfaceVariant, size = 52.dp)
            }
        } else {
            Art(header.artwork, size, if (kind == MusicDetailKind.Artist) CircleShape else RoundedCornerShape(Muse.Card))
        }
        Text(header.name, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
        if (header.subtitle.isNotBlank()) {
            Text(header.subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
        }
        if (tracks.isNotEmpty()) {
            PrimaryPill("播放", Glyphs.Play) { onPlay(tracks, 0) }
        }
    }
}
