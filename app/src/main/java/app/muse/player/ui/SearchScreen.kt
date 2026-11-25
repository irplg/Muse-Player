package app.muse.player.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.nevoit.sfcompose.symbols.StaticSymbol
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muse.player.data.AlbumInfo
import app.muse.player.data.ArtistInfo
import app.muse.player.data.MusicSearchCategory
import app.muse.player.data.MusicSearchResults
import app.muse.player.data.Netease
import app.muse.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

private val SearchShape = RoundedCornerShape(10.dp)
private const val HistoryLimit = 8
private const val PreviewLimit = 3

private enum class ResultCategory(val label: String, val source: MusicSearchCategory? = null) {
    All("全部"), Songs("歌曲", MusicSearchCategory.Songs), Artists("歌手", MusicSearchCategory.Artists),
    Albums("专辑", MusicSearchCategory.Albums),
}

private sealed interface SearchResult {
    data object Idle : SearchResult
    data object Loading : SearchResult
    data object Failed : SearchResult
    data class Loaded(val data: MusicSearchResults) : SearchResult
}

@Composable
internal fun SearchLauncher(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(SearchShape).background(colors.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, tint = colors.onSurfaceVariant, size = 21.dp)
        Spacer(Modifier.width(10.dp))
        Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun SearchScreen(
    initialQuery: String,
    currentTrackId: String?,
    onSearch: suspend (String) -> MusicSearchResults,
    onPlay: (List<Track>, Int) -> Unit,
    onClose: () -> Unit,
    onOpenArtist: (ArtistInfo) -> Unit,
    onOpenAlbum: (AlbumInfo) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("search_history", Context.MODE_PRIVATE) }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var submitted by rememberSaveable(initialQuery) { mutableStateOf(initialQuery.trim().takeIf { it.isNotEmpty() }) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SearchResult>(SearchResult.Idle) }
    var category by rememberSaveable { mutableStateOf(ResultCategory.All) }
    var resetScroll by remember { mutableIntStateOf(0) }
    var history by remember(prefs) {
        mutableStateOf(runCatching {
            val entries = JSONArray(prefs.getString("queries", "[]") ?: "[]")
            (0 until entries.length()).map { entries.optString(it) }.filter { it.isNotBlank() }.take(HistoryLimit)
        }.getOrDefault(emptyList()))
    }

    fun saveHistory(entries: List<String>) {
        history = entries
        prefs.edit().putString("queries", JSONArray(entries).toString()).apply()
    }

    fun search(raw: String, resetCategory: Boolean = true) {
        val term = raw.trim()
        if (term.isEmpty()) return
        if (history.firstOrNull() != term) {
            saveHistory((listOf(term) + history.filterNot { it.equals(term, ignoreCase = true) }).take(HistoryLimit))
        }
        focus.clearFocus()
        query = term
        submitted = term
        if (resetCategory) category = ResultCategory.All
        result = SearchResult.Loading
        attempt++
        resetScroll++
    }

    fun edit(value: String) {
        query = value
        submitted = null
        category = ResultCategory.All
        result = SearchResult.Idle
        resetScroll++
    }

    fun select(value: ResultCategory) {
        if (category != value) {
            category = value
            resetScroll++
        }
    }

    fun close() {
        focus.clearFocus()
        onClose()
    }

    val activeTerm = submitted
    val requestVersion = attempt
    LaunchedEffect(activeTerm, requestVersion) {
        val term = activeTerm ?: return@LaunchedEffect
        if (initialQuery.isNotBlank() && attempt == 0 && history.firstOrNull() != term) {
            saveHistory((listOf(term) + history.filterNot { it.equals(term, ignoreCase = true) }).take(HistoryLimit))
        }
        result = SearchResult.Loading
        try {
            val data = onSearch(term)
            ensureActive()
            if (submitted == term && attempt == requestVersion) {
                result = if (data.unavailable.size == MusicSearchCategory.entries.size) SearchResult.Failed
                else SearchResult.Loaded(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ensureActive()
            if (submitted == term && attempt == requestVersion) result = SearchResult.Failed
        }
    }
    LaunchedEffect(resetScroll) { if (resetScroll > 0) listState.scrollToItem(0) }

    val data = (result as? SearchResult.Loaded)?.data
    val tracks = data?.tracks.orEmpty()
    val artists = data?.artists.orEmpty()
    val albums = data?.albums.orEmpty()
    val unavailable = data?.unavailable.orEmpty()
    val hasMatches = tracks.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty()
    val playAt: (Int) -> Unit = { onPlay(tracks, it) }
    val openArtist: (ArtistInfo) -> Unit = { focus.clearFocus(); onOpenArtist(it) }
    val openAlbum: (AlbumInfo) -> Unit = { focus.clearFocus(); onOpenAlbum(it) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LargeTitle("搜索")
            }
            MusicSearchField(query, onChange = { edit(it) }, onSubmit = { search(query) })
            AnimatedVisibility(
                visible = data != null && (hasMatches || unavailable.isNotEmpty()),
                enter = expandVertically(spring(stiffness = 420f)) + fadeIn(),
                exit = shrinkVertically(spring(stiffness = 500f)) + fadeOut(),
            ) {
                ResultCategories(category, onSelect = ::select)
            }
            if (activeTerm != null && (result is SearchResult.Idle || result is SearchResult.Loading)) {
                // Do not measure a one-item loading list with the saved results' scroll position.
                SearchStatus("正在搜索", "正在查找与「$activeTerm」相关的音乐。", loading = true)
            } else LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 210.dp),
            ) {
                when {
                    activeTerm == null -> {
                        val typed = query.trim()
                        if (typed.isNotEmpty()) {
                            item { QueryRow("搜索「$typed」", Glyphs.Search, emphasized = true) { search(typed) } }
                        }
                        val recent = history.filter { it.contains(typed, ignoreCase = true) }
                        if (recent.isNotEmpty()) {
                            item { SearchHeading(if (typed.isEmpty()) "最近搜索" else "历史匹配", "清除") { saveHistory(emptyList()) } }
                            items(recent, key = { "recent:$it" }) { term ->
                                QueryRow(term, Glyphs.Search) { search(term) }
                            }
                        }
                        item { SearchHeading("浏览类别") }
                        item { SearchSuggestions { search(it) } }
                    }
                    result is SearchResult.Failed -> item {
                        SearchStatus("搜索失败", "请检查网络连接后重试。", action = "重试") { search(activeTerm, resetCategory = false) }
                    }
                    category.source?.let { it in unavailable } == true -> item {
                        SearchStatus("${category.label}搜索失败", "请检查网络连接后重试。", action = "重试") {
                            search(activeTerm, resetCategory = false)
                        }
                    }
                    category == ResultCategory.All -> {
                        if (tracks.isNotEmpty()) {
                            item { SearchHeading("歌曲", if (tracks.size > PreviewLimit) "查看全部" else null) { select(ResultCategory.Songs) } }
                            songRows(tracks.indices.take(PreviewLimit), tracks, currentTrackId, playAt)
                        }
                        if (artists.isNotEmpty()) {
                            item { SearchHeading("歌手", if (artists.size > PreviewLimit) "查看全部" else null) { select(ResultCategory.Artists) } }
                            artistRows(artists.take(PreviewLimit), openArtist)
                        }
                        if (albums.isNotEmpty()) {
                            item { SearchHeading("专辑", if (albums.size > PreviewLimit) "查看全部" else null) { select(ResultCategory.Albums) } }
                            albumRows(albums.take(PreviewLimit), openAlbum)
                        }
                        if (unavailable.isNotEmpty()) item {
                            val failed = ResultCategory.entries.filter { it.source?.let(unavailable::contains) == true }
                            SearchStatus("部分结果未能加载", "${failed.joinToString("、") { it.label }}搜索失败。", action = "重试") {
                                search(activeTerm, resetCategory = false)
                            }
                        } else if (!hasMatches) item {
                            SearchStatus("没有找到结果", "试试更短的关键词，或换一个歌曲、歌手或专辑名称。")
                        }
                    }
                    category == ResultCategory.Songs && tracks.isNotEmpty() -> {
                        item { SearchHeading("歌曲") }
                        songRows(tracks.indices.toList(), tracks, currentTrackId, playAt)
                    }
                    category == ResultCategory.Artists && artists.isNotEmpty() -> {
                        item { SearchHeading("歌手") }
                        artistRows(artists, openArtist)
                    }
                    category == ResultCategory.Albums && albums.isNotEmpty() -> {
                        item { SearchHeading("专辑") }
                        albumRows(albums, openAlbum)
                    }
                    else -> item {
                        SearchStatus("没有匹配的${category.label}", "试试更短的关键词，或切换其他类别。")
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicSearchField(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 4.dp)
            .semantics { contentDescription = "搜索歌曲、歌手、专辑" },
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text("歌曲、歌手、专辑") },
        leadingIcon = { Glyph(Glyphs.Search, tint = colors.onSurfaceVariant, size = 21.dp) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onChange("") }, modifier = Modifier.semantics { contentDescription = "清除搜索词" }) {
                    Box(Modifier.size(20.dp).background(colors.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Text("×", Modifier.clearAndSetSemantics {}, color = colors.surface, fontSize = 16.sp, lineHeight = 18.sp)
                    }
                }
            }
        } else null,
        singleLine = true,
        shape = SearchShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun SearchHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Muse.Page, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SearchSuggestions(onSearch: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Muse.Page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Muse.Genres.chunked(2).forEachIndexed { row, entries ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                entries.forEachIndexed { col, value ->
                    CategoryCard(
                        title = value,
                        tint = Muse.CategoryTints[(row * 2 + col) % Muse.CategoryTints.size],
                        image = rememberCategoryCover(value),
                        modifier = Modifier.weight(1f).height(100.dp),
                    ) { onSearch(value) }
                }
            }
        }
    }
}

@Composable
private fun ResultCategories(category: ResultCategory, onSelect: (ResultCategory) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResultCategory.entries.forEach { entry ->
            SearchPill(entry.label, selected = category == entry, modifier = Modifier.weight(1f)) { onSelect(entry) }
        }
    }
}

@Composable
private fun SearchPill(label: String, selected: Boolean?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected == true) colors.primary else colors.surfaceContainerHigh, spring(), label = "searchChip")
    val foreground = if (selected == true) colors.onPrimary else colors.onSurface
    val interactive = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
    }
    Text(
        label,
        modifier.clip(CircleShape).background(background).then(interactive).heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        color = foreground,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun QueryRow(label: String, icon: StaticSymbol, emphasized: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Muse.Page)) {
        Row(Modifier.heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (emphasized) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Glyph(icon, tint = if (emphasized) colors.primary else colors.onSurfaceVariant, size = 16.dp) }
            Text(
                label,
                Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (emphasized) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (emphasized) {
                Spacer(Modifier.width(10.dp))
                Chevron(tint = colors.primary.copy(alpha = .7f))
            }
        }
        Hairline(Modifier.padding(start = 42.dp))
    }
}

@Composable
private fun SearchStatus(title: String, message: String, loading: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
        } else {
            Glyph(Glyphs.Search, tint = colors.onSurfaceVariant, size = 26.dp)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun LazyListScope.songRows(indices: List<Int>, tracks: List<Track>, currentTrackId: String?, onPlay: (Int) -> Unit) {
    items(indices, key = { "song:$it" }) { index ->
        val track = tracks[index]
        DiscoverySong(track, track.id == currentTrackId) { onPlay(index) }
    }
}

private fun LazyListScope.artistRows(artists: List<ArtistInfo>, onOpen: (ArtistInfo) -> Unit) {
    items(artists, key = { "artist:${it.id}" }) { artist ->
        MusicEntityRow(artist.name, artist.artwork, "歌手", isArtist = true) { onOpen(artist) }
    }
}

private fun LazyListScope.albumRows(albums: List<AlbumInfo>, onOpen: (AlbumInfo) -> Unit) {
    items(albums, key = { "album:${it.id}" }) { album ->
        MusicEntityRow(album.name, album.artwork, album.artist.ifBlank { "专辑" }, isArtist = false) { onOpen(album) }
    }
}

@Composable
private fun MusicEntityRow(title: String, artwork: String?, subtitle: String, isArtist: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isArtist && artwork == null) {
            Box(Modifier.size(43.dp).background(colors.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Person, tint = colors.onSurfaceVariant, size = 21.dp)
            }
        } else {
            Art(artwork, Modifier.size(43.dp), if (isArtist) CircleShape else RoundedCornerShape(6.dp), px = 150)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron(size = 13.dp)
    }
}
