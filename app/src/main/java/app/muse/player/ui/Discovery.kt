package app.muse.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.muse.player.data.PlaylistInfo
import app.muse.player.data.Track

/**
 * 卡片底：整张图先做一层模糊打底，再把清晰版盖在上半部并向下渐隐。
 * 标题因此落在图片自己的虚化上，而不是一块黑。
 */
@Composable
private fun BlurredCoverFill(cover: String) {
    // 只画一张图：加载一次、绘制一次，不做模糊打底，避免任何两层图叠在一起。
    val bitmap by produceState<Bitmap?>(ImageLoaderLite.peek(cover, 720), cover) {
        value = withContext(Dispatchers.IO) { ImageLoaderLite.load(cover, 720) }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}/**
 * 大卡横滑：我喜欢的音乐排在最前，后面是精选歌单，尺寸完全一致。
 */
@Composable
internal fun CoverShelf(
    lists: List<PlaylistInfo>,
    radar: Long?,
    open: (PlaylistInfo) -> Unit,
    leading: PlaylistInfo? = null,
    leadingEyebrow: String = "歌单",
    leadingSubtitle: String? = null,
    onLeading: (() -> Unit)? = null,
) {
    LazyRow(
        Modifier.padding(top = 16.dp),
        contentPadding = PaddingValues(start = Muse.Page, end = Muse.Page),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            item(key = "leading-${leading.id}") {
                TopPickCard(leading, leadingEyebrow, leadingSubtitle) { onLeading?.invoke() ?: open(leading) }
            }
        }
        items(lists, key = { it.id }) { p ->
            TopPickCard(p, if (p.id == radar) "私人雷达" else "精选歌单", null) { open(p) }
        }
    }
}

private val ShelfCardWidth = 216.dp
private val ShelfCardHeight = 288.dp

@Composable
private fun TopPickCard(p: PlaylistInfo, eyebrow: String, subtitle: String?, onClick: () -> Unit) {
    Box(
        Modifier.width(ShelfCardWidth).height(ShelfCardHeight)
            .clip(RoundedCornerShape(Muse.Card))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (p.cover != null) {
            BlurredCoverFill(p.cover)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(.42f to Color.Transparent, .82f to Color.Black.copy(alpha = .38f), 1f to Color.Black.copy(alpha = .62f)),
                ),
            )
        } else {
            Box(
                Modifier.fillMaxSize()
                    .background(Muse.CategoryTints[p.id.hashCode().mod(Muse.CategoryTints.size)]),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Text(
                eyebrow,
                color = Color.White.copy(alpha = .76f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = .3.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                p.name,
                color = Color.White,
                fontSize = 21.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = subtitle ?: if (p.count > 0) "${p.count} 首歌曲" else null
            if (sub != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    sub,
                    color = Color.White.copy(alpha = .72f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Recently Played 横滑小卡：方封面 + 两行文字。
 */
@Composable
internal fun RecentShelf(items: List<Track>, onClick: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = Muse.Page, end = Muse.Page),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.size) { i ->
            val track = items[i]
            Column(Modifier.width(148.dp).clickable(role = Role.Button) { onClick(i) }) {
                Art(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(Muse.Art), px = 320)
                Spacer(Modifier.height(6.dp))
                Text(
                    track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 浏览页分类：2 列固定网格，8 个入口，背景取该类别的首位艺人头像。 */
@Composable
internal fun GenreShelf(search: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Muse.Page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muse.Genres.chunked(2).forEachIndexed { row, names ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                names.forEachIndexed { col, name ->
                    CategoryCard(
                        title = name,
                        tint = Muse.CategoryTints[(row * 2 + col) % Muse.CategoryTints.size],
                        image = rememberCategoryCover(name),
                        modifier = Modifier.weight(1f).height(112.dp),
                    ) { search(name) }
                }
            }
        }
    }
}

/** 首页/列表用的一首歌，右对齐时长。 */
@Composable
internal fun DiscoverySong(track: Track, selected: Boolean, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open)
            .padding(horizontal = Muse.Page, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(track.artwork, Modifier.size(48.dp), RoundedCornerShape(Muse.Art), px = 150)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artist,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (track.durationMs > 0) {
            Text(
                fmt(track.durationMs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    Hairline(Modifier.padding(start = 80.dp, end = Muse.Page))
}
