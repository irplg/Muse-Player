@file:OptIn(ExperimentalLayoutApi::class)

package app.muse.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
import app.muse.player.data.Netease
import app.muse.player.data.RoomUser
import app.muse.player.data.Together
import app.muse.player.player.PlayerHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PopupRoomAction { Join, Create }

@Composable
internal fun TogetherPopup(
    expanded: Boolean,
    wide: Boolean,
    reducedMotion: Boolean,
    artwork: String?,
    buttonBounds: Rect?,
    onDismiss: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val profile by Netease.profile.collectAsState()
    val room by Together.room.collectAsState()
    val activeTrack by PlayerHub.current.collectAsState()
    val playing by PlayerHub.isPlaying.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    var action by remember { mutableStateOf<PopupRoomAction?>(null) }
    var failed by remember { mutableStateOf<PopupRoomAction?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun resetError() {
        failed = null
        message = null
    }

    fun dismiss() {
        focus.clearFocus()
        keyboard?.hide()
        onDismiss()
    }

    // 收起弹窗不会取消正在进行的请求，也不会退出已加入的房间。
    fun connect(request: PopupRoomAction) {
        if (action != null) return
        focus.clearFocus()
        keyboard?.hide()
        action = request
        resetError()
        val invitation = link.trim()
        scope.launch {
            try {
                message = when (request) {
                    PopupRoomAction.Join -> Together.join(invitation)
                    PopupRoomAction.Create -> Together.create()
                }
                if (message != null) failed = request
                else if (request == PopupRoomAction.Join) link = ""
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = request
                message = "连接失败，请检查网络后重试。"
            } finally {
                action = null
            }
        }
    }

    BackHandler(enabled = expanded) { dismiss() }
    val colors = rememberTogetherColors(artwork, expanded)
    val visibility = updateTransition(targetState = expanded, label = "togetherVisibility")
    // 弹窗式展开：260ms 缓出曲线，末尾不再回弹，收尾干净。
    val openness by visibility.animateFloat(
        transitionSpec = {
            when {
                reducedMotion -> snap()
                targetState -> tween(340, easing = togetherOpenEasing)
                else -> tween(210, easing = togetherCloseEasing)
            }
        },
        label = "togetherOpenness",
    ) { open -> if (open) 1f else 0f }

    // expanded 挂载真实卡片；仅在退出仍运行时保留布局，不让动画决定打开时能否绘制。
    if (!expanded && (reducedMotion || (!visibility.currentState && !visibility.isRunning))) return
    val moving = !reducedMotion && (visibility.currentState != expanded || visibility.isRunning)
    Box(Modifier.fillMaxSize().zIndex(1f)) {
        Box(
            Modifier.matchParentSize().drawBehind {
                val shade = if (moving) openness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f else 1f
                // 底部播放页已经整体模糊，这里只补一层很轻的压暗。
                drawRect(Color.Black.copy(alpha = .22f * shade))
            }.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "关闭一起听",
                onClick = ::dismiss,
            ),
        )
        Box(
            Modifier.matchParentSize().zIndex(1f).imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = if (wide) Alignment.BottomEnd else Alignment.BottomCenter,
        ) {
            Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 520.dp)) {
                // 与系统同播共享面板一致：整块从屏幕下沿滑入，不缩放、不透明渐变。
                val motion = if (moving) Modifier.graphicsLayer {
                    val progress = openness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                    translationY = size.height * (1f - progress)
                } else Modifier
                MaterialTheme(colorScheme = colors) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(motion).semantics { paneTitle = "一起听" },
                        shape = togetherPanelShape,
                        color = colors.surfaceContainerHigh,
                        contentColor = colors.onSurface,
                        shadowElevation = 16.dp,
                    ) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Glyph(Glyphs.Together, size = 24.dp, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "一起听", Modifier.weight(1f).padding(start = 10.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                TextButton(onClick = ::dismiss) { Text("关闭") }
                            }
                            val me = profile
                            val activeRoom = room
                            when {
                                me == null -> {
                                    Text("登录网易云后，即可创建房间或通过邀请链接和朋友一起听。")
                                    Button(onClick = onOpenAccount) { Text("去登录") }
                                }
                                else -> {
                                    val busy = action != null
                                    if (busy) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                            Text(
                                                if (action == PopupRoomAction.Join) "正在加入房间…" else "正在创建房间…",
                                                Modifier.padding(start = 8.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (activeRoom == null) {
                                        OutlinedTextField(
                                            value = link,
                                            onValueChange = { link = it; resetError() },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !busy,
                                            isError = failed == PopupRoomAction.Join,
                                            label = { Text("一起听邀请链接") },
                                            maxLines = 3,
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(enabled = !busy && link.isNotBlank(), onClick = { connect(PopupRoomAction.Join) }) {
                                                Text(if (failed == PopupRoomAction.Join) "重新加入" else "加入房间")
                                            }
                                            TextButton(enabled = !busy, onClick = {
                                                val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
                                                val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
                                                if (pasted.isNotBlank()) { link = pasted; resetError() }
                                                else Toast.makeText(ctx, "剪贴板里没有文本", Toast.LENGTH_SHORT).show()
                                            }) { Text("粘贴链接") }
                                        }
                                        Text(
                                            "也可以创建房间，把邀请发给朋友。播放网易云歌曲才能同步。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FilledTonalButton(enabled = !busy, onClick = { connect(PopupRoomAction.Create) }) {
                                            Text(if (failed == PopupRoomAction.Create) "重新创建" else "创建房间")
                                        }
                                    } else {
                                        val syncingTrack = activeTrack?.takeIf { it.neteaseId != null }
                                        TogetherRoomDetails(
                                            room = activeRoom,
                                            me = RoomUser(me.userId, me.nickname, me.avatar),
                                            status = when {
                                                syncingTrack == null -> "等待播放网易云歌曲"
                                                playing -> "播放中 · ${syncingTrack.title}"
                                                else -> "已在房间 · ${syncingTrack.title}"
                                            },
                                            busy = busy,
                                            onShare = {
                                                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                                                    .putExtra(Intent.EXTRA_TEXT, Together.shareLink(activeRoom))
                                                ctx.startActivity(Intent.createChooser(share, "分享一起听邀请"))
                                            },
                                            onCopy = {
                                                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                                    ClipData.newPlainText("一起听邀请", Together.shareLink(activeRoom)),
                                                )
                                                if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "邀请链接已复制", Toast.LENGTH_SHORT).show()
                                            },
                                            onLeave = { Together.leave(); resetError() },
                                        )
                                    }
                                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 弹出：先快后慢的减速曲线（ease-out-quint），滑入末端几乎不减速，像系统面板；收起加速离开。
private val togetherOpenEasing = CubicBezierEasing(.22f, 1f, .36f, 1f)
private val togetherCloseEasing = CubicBezierEasing(.55f, 0f, .90f, .45f)
private val togetherPanelShape = RoundedCornerShape(20.dp)

private fun Rect.isUsableTogetherBounds(): Boolean =
    width > 0f && height > 0f && width.isFinite() && height.isFinite() &&
        center.x.isFinite() && center.y.isFinite()

/** 封面里抽出来的两色：面板底色 + 强调色（按钮、图标、文字链接都用它）。 */
private data class CoverColors(val base: Color?, val accent: Color?)

@Composable
private fun rememberTogetherColors(artwork: String?, expanded: Boolean): ColorScheme {
    val theme = MaterialTheme.colorScheme
    var hasOpened by remember { mutableStateOf(false) }
    var cover by remember(artwork) { mutableStateOf(CoverColors(null, null)) }
    LaunchedEffect(expanded) { if (expanded) hasOpened = true }
    LaunchedEffect(artwork, hasOpened) {
        if (!hasOpened || artwork.isNullOrBlank()) return@LaunchedEffect
        cover = withContext(Dispatchers.Default) {
            val image = ImageLoaderLite.load(artwork, 220) ?: return@withContext CoverColors(null, null)
            runCatching {
                val bitmap = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
                val palette = bitmap?.let { Palette.from(it).clearFilters().generate() }
                CoverColors(
                    base = palette?.dominantSwatch?.let { Color(it.rgb) },
                    accent = (palette?.vibrantSwatch ?: palette?.mutedSwatch ?: palette?.dominantSwatch)
                        ?.let { Color(it.rgb) },
                )
            }.getOrDefault(CoverColors(null, null))
        }
    }
    return remember(theme, cover) {
        // 面板固定纯白底，文字颜色交给 withPanelContrast 自动压深，不再跟封面取色。
        val background = Color.White
        val accent = (cover.accent ?: theme.primary).withPanelContrast(background, 3.2f)
        val tonal = lerp(background, accent, .22f)
        theme.copy(
            surface = background,
            surfaceContainer = background,
            surfaceContainerHigh = background,
            surfaceContainerHighest = lerp(background, Color.White, .06f),
            onSurface = theme.onSurface.withPanelContrast(background),
            onSurfaceVariant = theme.onSurfaceVariant.withPanelContrast(background, 3f),
            primary = accent,
            onPrimary = if (accent.luminance() > .45f) Color.Black else Color.White,
            secondaryContainer = tonal,
            onSecondaryContainer = theme.onSurface.withPanelContrast(tonal),
            error = theme.error.withPanelContrast(background),
            outline = theme.outline.withPanelContrast(background, 3f),
        )
    }
}

/**
 * 面板底色固定为纯白：既不跟封面取色，也不随深浅主题反转。
 * 所有文字色经 withPanelContrast 自动压深，保证白底上的对比度。
 */

private fun Color.withPanelContrast(background: Color, ratio: Float = 4.5f): Color {
    val backgroundLum = background.luminance()
    fun contrast(color: Color): Float {
        val foregroundLum = color.luminance()
        return (maxOf(backgroundLum, foregroundLum) + .05f) / (minOf(backgroundLum, foregroundLum) + .05f)
    }
    if (contrast(this) >= ratio) return this
    val endpoint = if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(12) {
        val mid = (low + high) * .5f
        if (contrast(lerp(this, endpoint, mid)) >= ratio) high = mid else low = mid
    }
    return lerp(this, endpoint, high)
}

@Composable
private fun TogetherRoomDetails(
    room: Together.Room,
    me: RoomUser,
    status: String,
    busy: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onLeave: () -> Unit,
) {
    val hostId = if (room.host) me.userId else room.inviterId.toLongOrNull()
    val users = (room.users + me).distinctBy { it.userId }.sortedByDescending { it.userId == hostId }
    Text(status, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
        "房间号：${room.roomId}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = onShare) { Text("分享邀请") }
        FilledTonalButton(enabled = !busy, onClick = onCopy) { Text("复制链接") }
    }
    Text("房间成员", style = MaterialTheme.typography.titleSmall)
    if (users.none { it.userId == hostId }) {
        Text("房主 · UID ${room.inviterId}", style = MaterialTheme.typography.bodySmall)
    }
    users.forEach { user ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Art(user.avatar, Modifier.size(32.dp), CircleShape, px = 100)
            Text(
                user.nickname.ifBlank { "UID ${user.userId}" }, Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (user.userId == hostId) "房主" else "成员") + if (user.userId == me.userId) " · 我" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TextButton(enabled = !busy, onClick = onLeave) {
        Text(if (room.host) "解散房间" else "退出房间", color = MaterialTheme.colorScheme.error)
    }
}
