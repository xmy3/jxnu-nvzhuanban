package cn.jxnu.nvzhuanban.data.repository

import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.data.network.pages.ArticleDetailPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 教务通知正文页 Repository。
 *
 * 数据来源：`Portal/ArticlesView.aspx?id=<id>`。详情页 **大多需要登录**（虽然列表是公开的），
 * 所以 fetch 走 [JwcClient.getHtmlAuth]——session 失效时静默 reauth 重放一次。
 *
 * 缓存策略：进程内 LRU（最多 16 篇）。用户从列表点进去 → 返回 → 再点同一篇，命中缓存秒开；
 * 16 上限是「同一通知 tab 一次浏览深度」的经验值，超过这个量内存占用也仍可忽略。
 * [refresh] 强制旁路缓存；列表退出登录时由调用方触发 [clearCache]。
 */
class ArticleDetailRepository {

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, ArticleDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArticleDetail>?): Boolean =
            size > MAX_CACHE_SIZE
    }

    suspend fun fetch(articleId: String): ArticleDetail = mutex.withLock {
        cache[articleId]?.let { return@withLock it }
        val detail = fetchRemote(articleId)
        cache[articleId] = detail
        detail
    }

    suspend fun refresh(articleId: String): ArticleDetail = mutex.withLock {
        val detail = fetchRemote(articleId)
        cache[articleId] = detail
        detail
    }

    fun clearCache() {
        // 缓存读写都在 mutex 内，但清空被退出登录之类的非协程上下文调用，
        // 这里允许非同步清空：竞态最差是一次过期数据被读到，下次 refresh 即自愈
        cache.clear()
    }

    private suspend fun fetchRemote(articleId: String): ArticleDetail =
        withContext(Dispatchers.IO) {
            val url = articleUrl(articleId)
            val html = JwcClient.getHtmlAuth(url, "通知详情页返回空响应")
            ArticleDetailPage.parse(html, url)
        }

    private fun articleUrl(id: String) = "${JxnuUrls.JWC_BASE}/Portal/ArticlesView.aspx?id=$id"

    companion object {
        private const val MAX_CACHE_SIZE = 16
        val instance: ArticleDetailRepository by lazy { ArticleDetailRepository() }
    }
}
