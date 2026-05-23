package cn.jxnu.nvzhuanban.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody

/**
 * Small facade for business requests to jwc.jxnu.edu.cn.
 *
 * 普通的 [getHtml] / [postHtml] / [getBytes] 是阻塞同步函数，应在 IO 上调用。
 *
 * 带 `Auth` 后缀的 suspend 版本会在 SessionExpired 时自动调 [SessionRecovery] 静默
 * 重登并重放一次原请求。Repository 默认应该用这些，除非业务页是公开的（不带 cookie
 * 也能访问，如通知列表）。
 */
object JwcClient {
    fun getHtml(url: String, emptyMessage: String): String {
        val req = Request.Builder().url(url).get().build()
        return executeHtml(req, emptyMessage)
    }

    fun postHtml(url: String, body: RequestBody, emptyMessage: String): String {
        val req = Request.Builder().url(url).post(body).build()
        return executeHtml(req, emptyMessage)
    }

    fun getBytes(url: String, emptyMessage: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        return JxnuHttpClient.get().client.newCall(req).execute().use { resp ->
            JwcResponseGuard.validateJwcResponse(resp)
            resp.body.bytes().takeIf { it.isNotEmpty() }
                ?: throw JwcException(JwcError.EmptyResponse, emptyMessage)
        }
    }

    /** 带"会话过期 → 静默重登 → 重放"的 GET 包装。 */
    suspend fun getHtmlAuth(url: String, emptyMessage: String): String =
        runWithSessionRecovery { getHtml(url, emptyMessage) }

    /** 带"会话过期 → 静默重登 → 重放"的 POST 包装。 */
    suspend fun postHtmlAuth(url: String, body: RequestBody, emptyMessage: String): String =
        runWithSessionRecovery { postHtml(url, body, emptyMessage) }

    /** 带"会话过期 → 静默重登 → 重放"的二进制 GET 包装（头像等）。 */
    suspend fun getBytesAuth(url: String, emptyMessage: String): ByteArray =
        runWithSessionRecovery { getBytes(url, emptyMessage) }

    private fun executeHtml(req: Request, emptyMessage: String): String =
        JxnuHttpClient.get().client.newCall(req).execute().use { resp ->
            JwcResponseGuard.readJwcHtml(resp, emptyMessage)
        }

    /**
     * 阻塞调用包成 IO 协程；捕获 SessionExpired 后 reauth 一次再 retry。
     * reauth 也失败时把 SessionExpired 透传，同时广播 [SessionEvents.notifyExpired]
     * 让 AppNav 把用户带去登录页。
     */
    private suspend inline fun <T> runWithSessionRecovery(crossinline block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: JwcException) {
                if (e.error != JwcError.SessionExpired) throw e
                val ok = SessionRecovery.tryReauthSilently()
                if (!ok) {
                    SessionEvents.notifyExpired()
                    throw e
                }
                // reauth 成功，重放原请求。二次仍然 SessionExpired（极罕见：CAS 刚登又被踢）
                // 不再尝试 reauth，直接透传 + 广播。
                try {
                    block()
                } catch (e2: JwcException) {
                    if (e2.error == JwcError.SessionExpired) SessionEvents.notifyExpired()
                    throw e2
                }
            }
        }
}
