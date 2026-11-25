package app.muse.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import app.muse.player.ui.playerSwipeModifier
import app.muse.player.ui.rememberReducedPlayerMotion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Job
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.muse.player.data.PlaylistInfo
import app.muse.player.ui.AboutScreen
import app.muse.player.ui.LicensesScreen
import app.muse.player.ui.SettingsScreen
import app.muse.player.ui.TabBar
import app.muse.player.ui.Hairline
import app.muse.player.ui.glassPanel
import app.muse.player.ui.HomeScreen
import app.muse.player.ui.LocalScreen
import app.muse.player.ui.MiniPlayer
import app.muse.player.ui.MuseTheme
import app.muse.player.ui.NowPlayingScreen
import app.muse.player.ui.PhoneLoginScreen
import app.muse.player.ui.PlaylistScreen
import app.muse.player.ui.ProfileScreen
import app.muse.player.ui.SearchScreen
import app.muse.player.data.ArtistInfo
import app.muse.player.data.AlbumInfo
import app.muse.player.ui.ArtistDetailScreen
import app.muse.player.ui.AlbumDetailScreen
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import app.muse.player.data.Netease
import app.muse.player.player.PlayerHub
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MuseTheme { AppRoot() } }
    }
}

private sealed interface SearchRoute {
    data class Artist(val info: ArtistInfo) : SearchRoute
    data class Album(val info: AlbumInfo) : SearchRoute
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot() {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    fun switchTo(page: Int) {
        scope.launch { pager.animateScrollToPage(page, animationSpec = spring(dampingRatio = .88f, stiffness = 360f)) }
    }
    val pageState = rememberSaveableStateHolder()
    val track by PlayerHub.current.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchLaunch by rememberSaveable { mutableIntStateOf(0) }
    var searchRoute by remember { mutableStateOf<SearchRoute?>(null) }
    fun openSearch(query: String) {
        searchRoute = null
        searchQuery = query
        pageState.removeState("search:$searchLaunch")
        searchLaunch++
        switchTo(2)
    }
    var playlist by remember { mutableStateOf<PlaylistInfo?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    // 0 我的 / 1 设置 / 2 关于 / 3 开源许可证 / 4 手机号登录
    var subpage by rememberSaveable { mutableIntStateOf(0) }
    var playerProgress by remember { mutableFloatStateOf(if (showPlayer) 1f else 0f) }
    var playerHeight by remember { mutableIntStateOf(0) }
    var playerAnimation by remember { mutableStateOf<Job?>(null) }
    val playerVisible by remember { derivedStateOf { showPlayer || playerProgress > 0f } }
    val reducedMotion = rememberReducedPlayerMotion()
    val density = LocalDensity.current
    val dismissDistance = with(density) { 96.dp.toPx() }
    val flingDistance = with(density) { 14.dp.toPx() }
    val flingVelocity = with(density) { 650.dp.toPx() }

    fun settlePlayer(open: Boolean, velocityY: Float = 0f) {
        playerAnimation?.cancel()
        showPlayer = open
        val target = if (open) 1f else 0f
        if (reducedMotion) {
            playerProgress = target
            return
        }
        playerAnimation = scope.launch {
            animate(
                initialValue = playerProgress,
                targetValue = target,
                initialVelocity = -velocityY / playerHeight.coerceAtLeast(1),
                animationSpec = if (open) spring(dampingRatio = .88f, stiffness = 380f) else tween(250),
            ) { value, _ -> playerProgress = value.coerceIn(0f, 1f) }
        }
    }

    fun dragPlayer(delta: Float) {
        playerProgress = (playerProgress - delta / playerHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    fun finishPlayerDrag(opening: Boolean, velocityY: Float, cancelled: Boolean) {
        val travel = (if (opening) playerProgress else 1f - playerProgress) * playerHeight
        val towardTarget = if (opening) -velocityY else velocityY
        val commit = !cancelled && towardTarget > -flingVelocity &&
            (travel >= minOf(dismissDistance, playerHeight * .25f) ||
                (travel >= flingDistance && towardTarget >= flingVelocity))
        settlePlayer(open = if (commit) opening else !opening, velocityY = if (commit) velocityY else 0f)
    }

    BackHandler(enabled = !playerVisible && pager.currentPage == 2) {
        if (searchRoute == null) switchTo(0) else searchRoute = null
    }
    BackHandler(enabled = !playerVisible && pager.currentPage == 3 && subpage > 0) {
        subpage = if (subpage == 4) 0 else subpage - 1
    }
    BackHandler(enabled = !playerVisible && pager.currentPage == 0 && playlist != null) { playlist = null }
    BackHandler(enabled = playerVisible) { settlePlayer(open = false) }

    // 底栏和迷你播放条共用一块实时模糊源：页面内容画进 HazeState，条本身只负责取样。
    val hazeState = remember { HazeState() }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { playerHeight = it.height },
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            userScrollEnabled = !playerVisible,
        ) { page ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                val distance = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                alpha = 1f - distance * .16f
                scaleX = 1f - distance * .025f
                scaleY = scaleX
            }) {
            pageState.SaveableStateProvider(page) { when (page) {
                0 -> AnimatedContent(playlist, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "libraryDetail") { pl ->
                    if (pl == null) HomeScreen(onOpenPlaylist = { playlist = it }, goProfile = { switchTo(3) }, onOpenSearch = ::openSearch)
                    else PlaylistScreen(pl) { playlist = null }
                }
                1 -> LocalScreen()
                2 -> AnimatedContent(searchRoute, transitionSpec = {
                    val direction = if (targetState == null) -1 else 1
                    (fadeIn(tween(240)) + slideInHorizontally(tween(300)) { direction * it / 5 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { -direction * it / 6 })
                }, label = "searchDetail") { route ->
                    when (route) {
                        null -> pageState.SaveableStateProvider("search:$searchLaunch") {
                            SearchScreen(
                                initialQuery = searchQuery, currentTrackId = track?.id,
                                onSearch = { Netease.searchMusic(it) },
                                onPlay = PlayerHub::playQueue, onClose = { switchTo(0) },
                                onOpenArtist = { searchRoute = SearchRoute.Artist(it) },
                                onOpenAlbum = { searchRoute = SearchRoute.Album(it) },
                            )
                        }
                        is SearchRoute.Artist -> ArtistDetailScreen(route.info, track?.id, PlayerHub::playQueue) { searchRoute = null }
                        is SearchRoute.Album -> AlbumDetailScreen(route.info, track?.id, PlayerHub::playQueue) { searchRoute = null }
                    }
                }
                else -> AnimatedContent(subpage, label = "accountPage") { depth ->
                    when (depth) {
                        4 -> PhoneLoginScreen(onBack = { subpage = 0 }, onLoggedIn = { subpage = 0 })
                        3 -> LicensesScreen { subpage = 2 }
                        2 -> AboutScreen(onBack = { subpage = 1 }, onLicenses = { subpage = 3 })
                        1 -> SettingsScreen(onBack = { subpage = 0 }, onAbout = { subpage = 2 })
                        else -> ProfileScreen(
                            onSettings = { subpage = 1 },
                            onPhoneLogin = { subpage = 4 },
                        )
                    }
                }
            } }
            }
        }

        // 迷你播放器 + 标签栏：共用一整块铺满屏幕宽度的模糊玻璃，
        // 玻璃画在 Box 底层，两块内容叠在上面，因此横向不会留边、中间也不会有缝。
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(Modifier.matchParentSize().glassPanel(hazeState))
            Column(Modifier.fillMaxWidth()) {
                MiniPlayer(
                    onOpen = { settlePlayer(open = true) },
                    openSwipe = playerSwipeModifier(
                        upwards = true,
                        onStart = {
                            if (showPlayer || playerHeight == 0) false
                            else { playerAnimation?.cancel(); true }
                        },
                        onDelta = ::dragPlayer,
                        onFinish = { velocity, cancelled -> finishPlayerDrag(true, velocity, cancelled) },
                    ),
                )
                // 迷你播放条与底栏之间：一条极细的浅灰分界线。
                Hairline()
                TabBar(pager.currentPage) { switchTo(it) }
            }
        }

        if (playerVisible) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationY = (1f - playerProgress) * size.height
                }.pointerInput(Unit) {
                    // Keep visibly covered content from also hitting the pager behind it.
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }.then(if (showPlayer) Modifier else Modifier.semantics { hideFromAccessibility() }),
            ) {
                NowPlayingScreen(
                    onClose = { settlePlayer(open = false) },
                    onOpenAccount = { settlePlayer(open = false); subpage = 0; switchTo(3) },
                    closeSwipe = playerSwipeModifier(
                        upwards = false,
                        onStart = {
                            if (!showPlayer || playerHeight == 0) false
                            else { playerAnimation?.cancel(); true }
                        },
                        onDelta = ::dragPlayer,
                        onFinish = { velocity, cancelled -> finishPlayerDrag(false, velocity, cancelled) },
                    ),
                )
            }
        }
    }
}
