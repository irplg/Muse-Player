package app.muse.player.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.muse.player.data.Netease
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 网易云网页端的登录态都是 Cookie；这里统一读写 WebView 的 Cookie 仓库。 */
object WebSession {
    private const val HOME = "https://music.163.com"
    const val LOGIN_URL = "$HOME/login"

    private fun cookies() = CookieManager.getInstance()

    /** 当前 WebView 在 music.163.com 下的完整 Cookie 串。 */
    fun cookie(): String = runCatching { cookies().getCookie(HOME).orEmpty() }.getOrDefault("")

    /** 网页登录成功后 MUSIC_U 就是登录凭据；没有它说明还没登录完成。 */
    fun musicU(raw: String = cookie()): String? =
        Netease.parseCookie(raw)["MUSIC_U"]?.trim()?.takeIf { it.length >= 16 }

    fun persist() = runCatching { cookies().flush() }

    /** 退出登录时一起清掉网页端会话，否则再进登录页会被自动带回原账号。 */
    fun clear() {
        runCatching {
            cookies().removeAllCookies(null)
            cookies().flush()
        }
    }
}

/**
 * 手机验证码登录：内嵌网易云网页登录页，登录完成后自动嗅探 Cookie 交给 [Netease]。
 * 不做任何密码拦截或注入，只是等页面把 MUSIC_U 写进 Cookie。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PhoneLoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val loggedIn by rememberUpdatedState(onLoggedIn)

    val webView = remember {
        // 上一账号的网页会话还在的话，一进页面就会被自动带回，先清干净。
        if (!Netease.loggedIn) WebSession.clear()
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            val views = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(views, true)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                }

                override fun onReceivedTitle(view: WebView, title: String?) = Unit
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 只放行 http(s)；tel:/weixin:// 这类外部 scheme 不交给系统，避免跳出应用。
                    return request.url.scheme?.let { it != "http" && it != "https" } ?: true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    progress = 0.05f
                }
            }
            loadUrl(WebSession.LOGIN_URL)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    // 登录页在验证码通过后才会写 MUSIC_U；轮询 Cookie 即可，无需介入页面逻辑。
    LaunchedEffect(Unit) {
        var attempts = 0
        while (isActive) {
            delay(700)
            if (busy) continue
            val cookie = WebSession.cookie()
            if (WebSession.musicU(cookie) == null) continue
            if (attempts >= 3) continue
            attempts++
            busy = true
            message = "已检测到登录，正在校验…"
            WebSession.persist()
            val ok = runCatching { Netease.loginWithCookie(cookie) }.getOrDefault(false)
            if (ok) {
                message = "登录成功"
                loggedIn()
                break
            }
            message = "登录凭据校验失败，请重试"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp, end = Muse.Page),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Glyph(Glyphs.Back, size = 22.dp) }
        }
        Text(
            "手机号登录",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = 4.dp),
        )
        Text(
            "在下面的网易云页面用手机号 + 验证码登录；登录成功后会自动读取凭据并返回，凭据只保存在本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Muse.Page, end = Muse.Page, top = 6.dp, bottom = 12.dp),
        )

        Surface(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 2.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (progress < 0.99f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Muse.Page, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                message ?: "提示：网页登录同样需要先同意网易云的用户协议。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
