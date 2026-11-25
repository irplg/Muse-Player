package app.muse.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.nevoit.sfcompose.symbols.StaticSymbol
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple Music 的排版基准。所有页面共用这几个数，避免每个区块各写一套边距。
 */
object Muse {
    /** 页面左右边距，iOS 多数列表/大标题用 20。 */
    val Page = 20.dp

    /** 区块之间的垂直间距。 */
    val Section = 26.dp

    /** 卡片/封面圆角。 */
    val Art = 6.dp
    val Card = 10.dp
    val Group = 12.dp

    /** 列表行最小高度。 */
    val Row = 56.dp

    /** 浏览类别：首页与搜索页共用，头像按关键词缓存，避免重复请求。 */
    val Genres = listOf(
        "华语流行", "K-Pop", "欧美流行", "粤语流行",
        "说唱", "电子", "摇滚", "R&B",
    )

    /** 分类卡的固定色板，取自 Apple Music 浏览页的纯色底。 */
    val CategoryTints = listOf(
        0xFFF94E86, 0xFFF94E86, 0xFFF94E86, 0xFFF94E86,
        0xFF5B7CFA, 0xFF1C1C1E, 0xFFF94E86, 0xFF8B6CFA,
        0xFF2E9C8E, 0xFFE08A3C,
    ).map { Color(it) }
}

/** 0.5dp 发丝线，iOS 列表的分隔线就是这个量级。 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/** iOS 大标题：34pt Bold，左边距 20，上下留白收紧。 */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = Muse.Page, end = Muse.Page - 6.dp, top = 30.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
}

/** 区块标题：22pt Bold + 可选的「全部 ›」。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = Muse.Page, end = Muse.Page, top = Muse.Section, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null && onAction != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction)
                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Chevron(tint = MaterialTheme.colorScheme.primary, size = 13.dp)
            }
        }
    }
}

/** iOS 列表右侧的小箭头。 */
@Composable
fun Chevron(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f), size: Dp = 12.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Glyph(Glyphs.Forward, tint = tint, size = size)
    }
}

/**
 * 播放/暂停图标：两个符号叠在同一格子里交叉缩放淡入淡出，
 * 形状不同（三角 vs 双竖条）也不会带着容器一起跳，和 iOS 的切换手感一致。
 */
@Composable
fun PlayPauseGlyph(
    playing: Boolean,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    val spec = tween<Float>(if (reducedMotion) 0 else 190)
    val playAlpha by animateFloatAsState(if (playing) 0f else 1f, spec, label = "playAlpha")
    val pauseAlpha by animateFloatAsState(if (playing) 1f else 0f, spec, label = "pauseAlpha")
    val playScale by animateFloatAsState(if (playing) .66f else 1f, spec, label = "playScale")
    val pauseScale by animateFloatAsState(if (playing) 1f else .66f, spec, label = "pauseScale")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Glyph(
            Glyphs.Play,
            Modifier.graphicsLayer {
                alpha = playAlpha
                scaleX = playScale
                scaleY = playScale
            },
            tint = tint,
            size = size,
        )
        Glyph(
            Glyphs.Pause,
            Modifier.graphicsLayer {
                alpha = pauseAlpha
                scaleX = pauseScale
                scaleY = pauseScale
            },
            tint = tint,
            size = size,
        )
    }
}

/** 分组列表卡片（设置、账号这类页面用）。 */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 6.dp),
        shape = RoundedCornerShape(Muse.Group),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column { content() } }
}

/** 分组列表里的一行：图标 + 标题 + 右侧数值/箭头。 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: StaticSymbol? = null,
    value: String? = null,
    showChevron: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = Muse.Row)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, tint = iconTint, size = 20.dp)
            Spacer(Modifier.width(13.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(12.dp))
            Chevron()
        }
    }
}

/** 空状态/错误提示：不用大段说明，给一句结论 + 一个动作。 */
@Composable
fun Notice(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * 浏览页的分类卡：2 列横版。
 * 有图时铺满类别第一首歌的第一位艺人头像，底部压暗托住白字；
 * 没图时退回纯色底，不画纹理。
 */
@Composable
fun CategoryCard(
    title: String,
    tint: Color,
    modifier: Modifier = Modifier,
    image: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Muse.Card))
            .background(tint)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        if (image != null) {
            Art(image, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), px = 480)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        .30f to Color.Black.copy(alpha = .10f),
                        .68f to Color.Black.copy(alpha = .38f),
                        1f to Color.Black.copy(alpha = .80f),
                    ),
                ),
            )
        }
        Text(
            title,
            color = Color.White,
            fontSize = 21.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

/** 主按钮：Apple Music 的播放全部是红色实心胶囊。 */
@Composable
fun PrimaryPill(text: String, icon: StaticSymbol? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Glyph(icon, tint = MaterialTheme.colorScheme.onPrimary, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** 次按钮：灰底胶囊。 */
@Composable
fun SecondaryPill(text: String, icon: StaticSymbol? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Glyph(icon, tint = LocalContentColor.current, size = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
