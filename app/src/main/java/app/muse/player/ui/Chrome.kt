package app.muse.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.nevoit.sfcompose.symbols.StaticSymbol
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muse.player.data.Track
import app.muse.player.player.PlayerHub
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 底部玻璃：一整块铺满屏幕宽度的不透明模糊。
 *
 * 由 [MainActivity] 用 `matchParentSize()` 画在迷你播放条与标签栏的**下面**，
 * 两段共用这一层，所以中间没有接缝、左右也不会留边。
 *
 * 关键是 [background] 那层不透明底色：Haze 在模糊不可用的设备上会退化成
 * ScrimBlurEffect，只画一层半透明色纱、完全不画底色，看起来就是「半透明没有模糊」。
 * 把不透明底色画在 Haze 下面，退化时也是实心块，任何时候都不会透出下面的内容。
 */
@Composable
fun Modifier.glassPanel(haze: HazeState?): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val opaque = background(surface)
    return if (haze == null) {
        opaque
    } else {
        opaque.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = surface,
                tint = HazeTint(surface.copy(alpha = .85f)),
                blurRadius = 140.dp,
                noiseFactor = 0f,
            ),
        ) {
            // Haze 默认只在 API 31+ 开模糊；< 31 走 RenderScript 路径（Android 10 可用），
            // 必须显式打开，否则只会画一层半透明色。
            blurEnabled = true
        }
    }
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** iOS 大标题（34pt Bold）。 */
@Composable
fun LargeTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = 30.dp, bottom = 14.dp),
    )
}

/** 说明/空状态文案。 */
@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = 6.dp, bottom = 10.dp),
    )
}

/** 区块标题（22pt Bold）。 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = Muse.Section, bottom = 10.dp),
    )
}

/** iOS 分组卡片。 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 6.dp),
        shape = RoundedCornerShape(Muse.Group),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column { content() } }
}

/** 列表歌曲行：封面 48、标题 16 semibold、艺人 13 灰。 */
@Composable
fun TrackRow(track: Track, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Muse.Page, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(track.artwork, Modifier.size(48.dp), RoundedCornerShape(Muse.Art), px = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (track.durationMs > 0) {
            Text(
                fmt(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private class TabItem(val label: String, val icon: StaticSymbol, val selectedIcon: StaticSymbol)

/**
 * 底栏四个入口，图标与 Apple Music 对应：现在就听 / 曲库 / 搜索 / 我的。
 * 选中态换成 SF Symbols 的 Fill 变体，未选中保留线描，和 iOS 标签栏一致。
 */
private val TABS = listOf(
    TabItem("现在就听", Glyphs.Listen, Glyphs.ListenFill),
    TabItem("曲库", Glyphs.Library, Glyphs.LibraryFill),
    TabItem("搜索", Glyphs.Search, Glyphs.Search),
    TabItem("我的", Glyphs.PersonCircle, Glyphs.PersonCircleFill),
)

/**
 * iOS 式贴底标签栏：49dp 主体 + 安全区，顶部一条发丝线，选中项染成 Apple Music 红。
 * 左右滑动切换保留（原交互），但视觉不再用悬浮胶囊。
 */
@Composable
fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 28.dp.toPx() }
    // 手势导航时 navigationBars 的 inset 可能是 0，需要再让出手势条本身的高度，
    // 否则标签会和系统手势条压在一起。
    val bottomInset = with(density) {
        maxOf(
            WindowInsets.navigationBars.getBottom(this),
            WindowInsets.systemGestures.getBottom(this),
        ).toDp()
    }.coerceAtLeast(8.dp)
    // 玻璃由外层 glassPanel 统一绘制，这里只排内容。
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = bottomInset).height(58.dp)
                .pointerInput(selected, rtl, swipeThresholdPx) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                        if (kotlin.math.abs(dx) > swipeThresholdPx) {
                            val delta = if ((dx < 0f) != rtl) 1 else -1
                            onSelect((selected + delta).coerceIn(TABS.indices))
                        }
                    }) { change, movement -> change.consume(); dx += movement }
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TABS.forEachIndexed { i, tab ->
                val sel = i == selected
                val fg = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .selectable(
                            selected = sel,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(i) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Glyph(if (sel) tab.selectedIcon else tab.icon, tint = fg, size = 25.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = fg,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 兼容旧调用点。 */
@Composable
fun FloatingTabBar(selected: Int, onSelect: (Int) -> Unit) = TabBar(selected, onSelect = onSelect)

/** 底栏上方的迷你播放条：整宽、无圆角，和 iOS 一致。 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, openSwipe: Modifier = Modifier) {
    val track by PlayerHub.current.collectAsState()
    val playing by PlayerHub.isPlaying.collectAsState()
    val t = track ?: return
    Column(Modifier.fillMaxWidth()) {
        // 迷你播放条顶部同样一条极细的浅灰分界线，和底栏之间那条一致。
        Hairline()
        Row(
            Modifier.fillMaxWidth().then(openSwipe).clickable(role = Role.Button, onClick = onOpen)
                .padding(start = Muse.Page, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(t.artwork, Modifier.size(44.dp), RoundedCornerShape(4.dp), px = 150)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                // 显式跟随主题前景色：深色模式白、浅色模式黑，不依赖 LocalContentColor 的默认值。
                Text(
                    t.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    t.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniControl(
                label = if (playing) "暂停" else "播放",
                onClick = { PlayerHub.toggle() },
            ) { PlayPauseGlyph(playing, MaterialTheme.colorScheme.onSurface, 22.dp) }
            MiniControl(Glyphs.Next, "下一首") { PlayerHub.next() }
        }
    }
}

@Composable
private fun MiniControl(icon: StaticSymbol, label: String, onClick: () -> Unit) {
    MiniControl(label, onClick) {
        Glyph(icon, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
    }
}

@Composable
private fun MiniControl(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { content() }
}
