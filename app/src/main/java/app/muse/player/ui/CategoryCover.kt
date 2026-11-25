package app.muse.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import app.muse.player.data.Netease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 类别封面：取该类别搜索结果里第一首歌的第一位艺人头像。
 *
 * 走客户端已有的搜索 / 歌手详情接口，不改服务端。结果按关键词常驻内存，
 * 首页与搜索页共用同一批类别，所以每个类别整个进程只拉一次。
 * 请求挂在进程级作用域上，列表滚出屏幕不会把进行中的请求掐断；
 * 同一时间只跑一个，避免一进首页就把网易云打到限流。
 */
internal object CategoryCoverCache {
    private val covers = mutableStateMapOf<String, String>()
    private val attempted = mutableStateMapOf<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 串行 + 间隔：类别封面是次要请求，不能和搜索抢同一个接口，
    // 否则一次进页面就打 8 个搜索，容易被服务端限流，搜索反而搜不出东西。
    private val gate = Semaphore(1)

    fun cover(key: String): String? = covers[key]

    /** 幂等：重复调用不会重复发请求。 */
    fun request(key: String) {
        if (covers[key] != null || attempted[key] == true) return
        scope.launch {
            gate.withPermit {
                if (covers[key] != null || attempted[key] == true) return@withPermit
                delay(250)
                val found = runCatching {
                    val results = Netease.searchMusic(key, limit = 1)
                    val artist = results.firstTrackArtist ?: return@runCatching null
                    Netease.artistDetail(artist.id).artist.artwork
                }.getOrNull()?.takeIf { it.isNotBlank() }
                attempted[key] = true
                if (found != null) covers[key] = found
                delay(200)
            }
        }
    }
}

@Composable
internal fun rememberCategoryCover(key: String): String? {
    SideEffect { CategoryCoverCache.request(key) }
    return CategoryCoverCache.cover(key)
}
