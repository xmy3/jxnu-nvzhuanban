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
            withSessionRecovery(
                reauth = { SessionRecovery.tryReauthSilently() },
                notifyExpired = { SessionEvents.notifyExpired() },
                block = { block() },
            )
        }
}

/**
 * "会话过期 → reauth 一次 → 重放一次" 的纯容错骨架，从 [JwcClient] 的 runWithSessionRecovery
 * 抽出以便单测：不含 IO dispatcher / 具体单例依赖，[reauth] 与 [notifyExpired] 由调用方注入。
 *
 * 行为契约（由 JwcClientSessionRecoveryTest 锁定）：
 *  - [block] 成功 → 直接返回，绝不 reauth / notify
 *  - [block] 抛非 SessionExpired 的 [JwcException] → 原样透传，不 reauth
 *  - SessionExpired 且 [reauth] 返回 false → [notifyExpired] 一次并透传原异常
 *  - SessionExpired 且 [reauth] 返回 true → 重放一次 [block]；成功则返回其结果
 *  - 重放仍抛 SessionExpired → [notifyExpired] 一次并透传（**不再二次 reauth**）
 *  - 重放抛其它异常 → 原样透传，不 notify
 */
internal suspend fun <T> withSessionRecovery(
    reauth: suspend () -> Boolean,
    notifyExpired: () -> Unit,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: JwcException) {
    if (e.error != JwcError.SessionExpired) throw e
    if (!reauth()) {
        notifyExpired()
        throw e
    }
    try {
        block()
    } catch (e2: JwcException) {
        if (e2.error == JwcError.SessionExpired) notifyExpired()
        throw e2
    }
}
