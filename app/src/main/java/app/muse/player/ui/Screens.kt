package app.muse.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.muse.player.player.SpatialAudio
import app.muse.player.data.LocalLibrary
import app.muse.player.data.Netease
import app.muse.player.data.PlaylistInfo
import app.muse.player.data.Together
import app.muse.player.data.Track
import app.muse.player.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

// ============================================================ 现在就听

/**
 * 首页按 Apple Music 的 Listen Now 组织：
 * 大标题 + 头像 → 精选推荐（大幅卡，含我喜欢的音乐）→ 最近播放 → 猜你喜欢 → 浏览类别。
 */
/**
 * 首页数据的进程内快照。HorizontalPager 会把划走的页面移出 composition，
 * 状态只放在 remember 里的话，每次回首页都要重拉歌单和「我喜欢的音乐」封面，
 * 卡片就会先空一块再逐渐载入。放在这里，切 tab、进搜索、开播放页都不影响。
 */
private object HomeCache {
    var uid: Long? = null
    var library: Result<Netease.UserPlaylists>? by mutableStateOf(null)
    var artwork: String? by mutableStateOf(null)
    var artworkOwner: Long? = null
    var publicLists: Result<List<PlaylistInfo>>? by mutableStateOf(null)
    var picks: List<Track> by mutableStateOf(emptyList())
    var picksOwner: Long? = null

    fun resetFor(userId: Long?) {
        if (uid == userId) return
        uid = userId
        library = null
        artwork = null
        artworkOwner = null
        picks = emptyList()
        picksOwner = null
    }
}

@Composable
fun HomeScreen(onOpenPlaylist: (PlaylistInfo) -> Unit, goProfile: () -> Unit, onOpenSearch: (String) -> Unit) {
    val profile by Netease.profile.collectAsState()
    val cur by PlayerHub.current.collectAsState()
    val queue by PlayerHub.queue.collectAsState()
    val uid = profile?.userId

    var publicRequest by remember { mutableStateOf(0) }
    var libraryRequest by remember { mutableStateOf(0) }

    // 只有账号变了才清快照；普通切页回来直接复用，封面不会再闪一次。
    LaunchedEffect(uid) { HomeCache.resetFor(uid) }

    LaunchedEffect(publicRequest) {
        if (publicRequest == 0 && HomeCache.publicLists != null) return@LaunchedEffect
        HomeCache.publicLists = null
        HomeCache.publicLists = discoveryResult { Netease.publicPlaylists() }
    }
    LaunchedEffect(uid, libraryRequest) {
        if (uid == null) return@LaunchedEffect
        if (libraryRequest == 0 && HomeCache.library != null) return@LaunchedEffect
        HomeCache.library = null
        val loaded = discoveryResult { Netease.userPlaylists(uid) }
        HomeCache.library = loaded
        loaded.getOrNull()?.loved?.let { liked ->
            if (HomeCache.artworkOwner != liked.id) {
                HomeCache.artwork = discoveryResult { Netease.playlistFirstTrackArtwork(liked.id) }.getOrNull()
                HomeCache.artworkOwner = liked.id
            }
        }
    }
    LaunchedEffect(uid) {
        if (HomeCache.picksOwner == uid && HomeCache.picks.isNotEmpty()) return@LaunchedEffect
        HomeCache.picks = discoveryResult {
            if (uid == null) Netease.search("爵士", limit = 8) else Netease.dailySongs()
        }.getOrDefault(emptyList())
        HomeCache.picksOwner = uid
    }

    val guest = uid == null
    val accountLists = HomeCache.library?.getOrNull()
    val loved = accountLists?.loved
    val playlists = accountLists?.all.orEmpty()
    val featured = HomeCache.publicLists?.getOrNull().orEmpty()
    val picks = HomeCache.picks
    val artwork = HomeCache.artwork
    val radarId = Netease.radarPlaylist(playlists)?.id

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 200.dp),
    ) {
        item {
            ScreenTitle("现在就听") { Avatar(profile?.avatar, goProfile) }
        }

        item {
            SectionHeader("精选推荐")
        }
        item {
            // 我喜欢的音乐并进精选推荐这一条，尺寸与其他大卡一致；
            // 未登录时也占一张卡，封面先借用猜你喜欢的第一首，避免出现空白块。
            val lovedCard = when {
                guest -> PlaylistInfo(-1L, "我喜欢的音乐", picks.firstOrNull()?.artwork, 0)
                // 封面只认歌单第一首歌的专辑图，绝不退回歌单自带的封面（那是另一张图，会和模糊层叠在一起）。
                loved != null -> loved.copy(name = "我喜欢的音乐", cover = artwork)
                else -> null
            }
            val lovedSubtitle = when {
                guest -> "登录网易云音乐同步"
                loved != null -> if (loved.count > 0) "${loved.count} 首歌曲" else "打开歌单"
                HomeCache.library == null -> "正在加载…"
                HomeCache.library?.isFailure == true -> "加载失败，点此重试"
                else -> "还没有「我喜欢的音乐」"
            }
            if (lovedCard == null && featured.isEmpty()) {
                when {
                    HomeCache.publicLists == null -> Hint("正在加载精选歌单…")
                    HomeCache.publicLists?.isFailure == true -> Notice("暂时无法加载精选歌单。", "重试") { publicRequest++ }
                    else -> Hint("暂时没有可推荐的歌单。")
                }
            } else {
                CoverShelf(
                    lists = featured,
                    radar = radarId,
                    open = onOpenPlaylist,
                    leading = lovedCard,
                    leadingSubtitle = lovedSubtitle,
                    onLeading = when {
                        guest -> goProfile
                        HomeCache.library?.isFailure == true -> ({ libraryRequest += 1 })
                        else -> null
                    },
                )
            }
        }

        if (queue.isNotEmpty()) {
            item { SectionHeader("最近播放") }
            item {
                val recent = queue.take(10)
                RecentShelf(recent) { i -> PlayerHub.playQueue(recent, i) }
            }
        }

        if (picks.isNotEmpty()) {
            item { SectionHeader(if (guest) "爵士单曲" else "猜你喜欢") }
            item {
                Column {
                    picks.take(8).forEachIndexed { i, t ->
                        DiscoverySong(t, cur?.id == t.id) { PlayerHub.playQueue(picks, i) }
                    }
                }
            }
        }

        item { SectionHeader("浏览类别") }
        item { GenreShelf { onOpenSearch(it) } }

    }
}

@Composable
private fun Avatar(url: String?, onClick: () -> Unit) {
    Box(
        Modifier.padding(top = 4.dp).size(38.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) Art(url, Modifier.fillMaxSize(), CircleShape, px = 150)
        else Glyph(Glyphs.Person, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 19.dp)
    }
}

private suspend fun <T> discoveryResult(load: suspend () -> T): Result<T> = try {
    Result.success(load())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

// ============================================================ 歌单详情

@Composable
fun PlaylistScreen(info: PlaylistInfo, onBack: () -> Unit) {
    var tracks by remember(info.id) { mutableStateOf<List<Track>>(emptyList()) }
    var status by remember(info.id) { mutableStateOf(CollectionLoad.Loading) }
    var request by remember(info.id) { mutableStateOf(0) }
    LaunchedEffect(info.id, request) {
        status = CollectionLoad.Loading
        try {
            tracks = Netease.playlistTracks(info.id)
            status = CollectionLoad.Ready
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = CollectionLoad.Failed
        }
    }

    TrackList(tracks, "playlist:${info.id}", onBack) {
        CollectionHero(
            eyebrow = "歌单",
            title = info.name,
            detail = when (status) {
                CollectionLoad.Loading -> "正在加载…"
                CollectionLoad.Failed -> "加载失败"
                CollectionLoad.Ready -> "${tracks.size} 首歌曲"
            },
            artwork = info.cover ?: tracks.firstOrNull()?.artwork,
        )
        if (tracks.isNotEmpty()) CollectionActions(tracks)
        else when (status) {
            CollectionLoad.Loading -> CenterBox { CircularProgressIndicator() }
            CollectionLoad.Failed -> Notice("无法加载歌单。", "重试") { request++ }
            CollectionLoad.Ready -> Notice("这张歌单还没有歌曲。", "重新加载") { request++ }
        }
    }
}

// ============================================================ 曲库（本地）

@Composable
fun LocalScreen() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val tracks by LocalLibrary.tracks.collectAsState()
    var scanning by remember { mutableStateOf(granted) }
    var scanRequest by remember { mutableStateOf(0) }

    LaunchedEffect(granted, scanRequest) {
        if (granted) {
            scanning = true
            try {
                LocalLibrary.refresh(ctx)
            } finally {
                scanning = false
            }
        }
    }

    val library = if (granted) tracks else emptyList()
    TrackList(library, "local", onBack = null) {
        LargeTitle("曲库")
        CollectionHero(
            eyebrow = "设备曲库",
            title = "本地音乐",
            detail = when {
                !granted -> "需要读取音频文件的权限"
                scanning -> "正在扫描…"
                else -> "${library.size} 首歌曲"
            },
            artwork = null,
            local = true,
        )
        when {
            !granted -> Notice("授予音频读取权限后即可扫描设备上的音乐。", "授予权限") { launcher.launch(perm) }
            library.isNotEmpty() -> CollectionActions(library, canRefresh = !scanning) { scanRequest++ }
            scanning -> CenterBox { CircularProgressIndicator() }
            else -> Notice("没有扫描到音乐。把歌曲放进设备的 Music 文件夹后重新扫描。", "重新扫描") { scanRequest++ }
        }
    }
}

private enum class CollectionLoad { Loading, Failed, Ready }

private enum class RowPlayback(val label: String?) {
    Idle(null), Loading("加载中"), Playing("播放中"), Paused("已暂停"), Failed("播放失败"),
}

/** 歌单/曲库共用：头部插槽 + 歌曲列表。 */
@Composable
private fun TrackList(
    tracks: List<Track>,
    collectionKey: String,
    onBack: (() -> Unit)?,
    header: @Composable () -> Unit,
) {
    val current by PlayerHub.current.collectAsState()
    val queue by PlayerHub.queue.collectAsState()
    val queueIndex by PlayerHub.index.collectAsState()
    val playing by PlayerHub.isPlaying.collectAsState()
    val buffering by PlayerHub.buffering.collectAsState()
    val error by PlayerHub.errorMsg.collectAsState()
    val currentId = current?.id

    val activeIndex = remember(tracks, queue, queueIndex, currentId, collectionKey) {
        when {
            currentId == null -> -1
            tracks == queue && tracks.getOrNull(queueIndex)?.id == currentId -> queueIndex
            else -> tracks.indexOfFirst { it.id == currentId }
        }
    }
    val activePlayback = when {
        error != null -> RowPlayback.Failed
        buffering -> RowPlayback.Loading
        playing -> RowPlayback.Playing
        else -> RowPlayback.Paused
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize()
                .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(bottom = 200.dp),
        ) {
            if (onBack != null) {
                item {
                    Row(Modifier.padding(start = 6.dp, top = 4.dp)) {
                        IconButton(onClick = onBack) {
                            Glyph(Glyphs.Back, size = 22.dp)
                        }
                    }
                }
            }
            item { header() }
            if (tracks.isNotEmpty()) {
                item {
                    Text(
                        "歌曲",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = 24.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(tracks, key = { i, track -> "${track.id}:$i" }) { i, track ->
                    val playback = if (i == activeIndex) activePlayback else RowPlayback.Idle
                    CollectionTrackRow(track, playback) {
                        when (playback) {
                            RowPlayback.Playing -> PlayerHub.pause()
                            RowPlayback.Paused -> PlayerHub.play()
                            RowPlayback.Failed -> {
                                if (queue.getOrNull(queueIndex)?.id == track.id) PlayerHub.playIndex(queueIndex)
                                else PlayerHub.playQueue(tracks, i)
                            }
                            RowPlayback.Idle -> PlayerHub.playQueue(tracks, i)
                            RowPlayback.Loading -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** Apple Music 歌单头：封面 + 右上信息，不套卡片背景。 */
@Composable
private fun CollectionHero(eyebrow: String, title: String, detail: String, artwork: String?, local: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = Muse.Page, end = Muse.Page, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(8.dp)
        if (local) {
            Box(
                Modifier.size(124.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, shape),
                contentAlignment = Alignment.Center,
            ) { Glyph(Glyphs.Library, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 40.dp) }
        } else {
            Art(artwork, Modifier.size(124.dp), shape, px = 420)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CollectionActions(tracks: List<Track>, canRefresh: Boolean = true, onRefresh: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Muse.Page, end = Muse.Page, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryPill("播放", Glyphs.Play) {
            PlayerHub.mode.value = PlayerHub.MODE_LOOP
            PlayerHub.playQueue(tracks, 0)
        }
        SecondaryPill("随机", Glyphs.Shuffle) {
            PlayerHub.mode.value = PlayerHub.MODE_SHUFFLE
            PlayerHub.playQueue(tracks, Random.nextInt(tracks.size))
        }
        if (onRefresh != null) {
            TextButton(enabled = canRefresh, onClick = onRefresh) { Text("重新扫描") }
        }
    }
}

@Composable
private fun CollectionTrackRow(track: Track, playback: RowPlayback, onActivate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = playback != RowPlayback.Idle
    val action = when (playback) {
        RowPlayback.Playing -> "暂停"
        RowPlayback.Paused -> "继续播放"
        RowPlayback.Failed -> "重试播放"
        else -> "播放"
    }
    val accent = if (playback == RowPlayback.Failed) colors.error else colors.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = playback != RowPlayback.Loading, role = Role.Button, onClickLabel = action, onClick = onActivate)
                .semantics { playback.label?.let { stateDescription = it } }
                .padding(start = Muse.Page, end = Muse.Page, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(track.artwork, Modifier.size(48.dp), RoundedCornerShape(Muse.Art), px = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) colors.primary else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist.ifBlank { "未知艺人" },
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current || track.durationMs > 0) {
                Text(
                    playback.label ?: fmt(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (playback == RowPlayback.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
            }
        }
        Hairline(Modifier.padding(start = Muse.Page + 60.dp, end = Muse.Page))
    }
}

// ============================================================ 我的

@Composable
fun ProfileScreen(onSettings: () -> Unit, onPhoneLogin: () -> Unit) {
    val profile by Netease.profile.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 200.dp),
    ) {
        item {
            ScreenTitle("我的") {
                IconButton(onClick = onSettings) { Glyph(Glyphs.Settings, size = 22.dp) }
            }
        }
        val p = profile
        if (p != null) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Art(p.avatar, Modifier.size(72.dp), CircleShape, px = 240)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.nickname, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text("UID ${p.userId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ListGroup {
                    ListRow("退出登录", icon = Glyphs.Back, tint = MaterialTheme.colorScheme.error, iconTint = MaterialTheme.colorScheme.error) {
                        Together.leave()
                        Netease.logout()
                        WebSession.clear()
                    }
                }
            }
        } else {
            item { PhoneLoginEntry(onPhoneLogin) }
            item { QrLogin() }
        }
    }
}

/** 登录入口：主路径是手机号 + 验证码（内嵌网页），扫码是备用路径。 */
@Composable
private fun PhoneLoginEntry(onPhoneLogin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 10.dp)) {
        Text("登录网易云音乐", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "用手机号接收验证码登录，登录后自动取回登录状态；凭据只保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryPill("手机验证码登录", icon = Glyphs.Person, onClick = onPhoneLogin)
    }
}

@Composable
private fun QrLogin() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        while (isActive) {
            val code = runCatching { Netease.qrCheck(k) }.getOrDefault(-1)
            when (code) {
                800 -> { status = "二维码已过期"; key = null; qr = null }
                801 -> status = "请用网易云音乐 App 扫描二维码"
                802 -> status = "已扫码，请在手机上确认"
                803 -> { status = "登录成功"; key = null; qr = null }
            }
            if (code == 800 || code == 803) break
            delay(2000)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("或用网易云 App 扫码", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "在另一台设备上用网易云音乐 App 扫描二维码（我的 → 扫一扫）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        )
        qr?.let {
            Image(
                it.asImageBitmap(),
                null,
                Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    val k = runCatching { Netease.qrKey() }.getOrNull()
                    if (k.isNullOrBlank()) {
                        status = "获取二维码失败，请检查网络"
                    } else {
                        qr = withContext(Dispatchers.Default) { qrBitmap(Netease.qrUrl(k)) }
                        key = k
                        status = "请用网易云音乐 App 扫描二维码"
                    }
                    busy = false
                }
            },
        ) { Text(if (qr == null) "获取二维码" else "刷新二维码") }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val saveLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            message = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(Netease.exportLogs().toByteArray(Charsets.UTF_8))
                } ?: error("无法写入文件")
                "日志已导出"
            }.getOrElse { "导出失败：${it.localizedMessage ?: "未知错误"}" }
        }
    }
    val spatialOn by SpatialAudio.enabled.collectAsState()
    val spatialStatus by SpatialAudio.status.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Glyph(Glyphs.Back, size = 22.dp) }
        }
        LargeTitle("设置")
        ListGroup {
            SwitchRow(
                title = "空间音频",
                description = "耳机下把声场推出头外。当前：${spatialStatus}",
                checked = spatialOn,
                onCheckedChange = { SpatialAudio.setEnabled(it) },
            )
        }
        ListGroup {
            // 直接读安装包的 versionName，避免改了 build.gradle 忘了改这里。
            val version = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "—"
            ListRow("关于 Muse", icon = Glyphs.Info, value = version, showChevron = true, onClick = onAbout)
            Hairline(Modifier.padding(start = 49.dp))
            ListRow("导出日志", icon = Glyphs.Share) { saveLogs.launch("muse-log.txt") }
        }
        message?.let { Hint(it) }
    }
}

/** 带开关的设置行：标题 + 一行状态说明 + 右侧 Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 关于页只留一个入口：开源许可证。 */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Glyph(Glyphs.Back, size = 22.dp) }
        }
        LargeTitle("关于")
        Spacer(Modifier.height(10.dp))
        ListGroup {
            ListRow("开源许可证", icon = Glyphs.Info, showChevron = true, onClick = onLicenses)
        }
    }
}

private class License(val name: String, val spdx: String, val url: String)

/** 随包分发的开源组件，逐条给出许可证与源码/主页链接。 */
private val Licenses = listOf(
    License("AndroidX Compose", "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support"),
    License("AndroidX Activity", "Apache-2.0", "https://github.com/androidx/androidx"),
    License("AndroidX Core", "Apache-2.0", "https://github.com/androidx/androidx"),
    License("AndroidX Media3", "Apache-2.0", "https://github.com/androidx/media"),
    License("AndroidX Palette", "Apache-2.0", "https://github.com/androidx/androidx"),
    License("Haze", "Apache-2.0", "https://github.com/chrisbanes/haze"),
    License("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    License("ZXing Core", "Apache-2.0", "https://github.com/zxing/zxing"),
    License("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    License("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    License("SFCompose", "Apache-2.0", "https://github.com/Nevodev/SFCompose"),
    License("Compose Icons", "MIT", "https://github.com/DevSrSouza/compose-icons"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Glyph(Glyphs.Back, size = 22.dp) }
        }
        LargeTitle("开源许可证")
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item {
                ListGroup {
                    Licenses.forEachIndexed { i, license ->
                        if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                        LicenseRow(license) { openLicense(ctx, license.url) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseRow(license: License, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                license.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    license.spdx,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    " · ${license.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Glyph(Glyphs.Open, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f), size = 15.dp)
    }
}

private fun openLicense(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
