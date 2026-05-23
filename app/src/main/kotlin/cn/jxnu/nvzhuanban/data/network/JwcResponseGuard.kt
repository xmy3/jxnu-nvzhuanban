package cn.jxnu.nvzhuanban.data.network

import okhttp3.Response

/**
 * Centralizes the sanity checks for HTML fetched from the JWC site.
 *
 * OkHttp follows redirects, so an expired session often arrives here as a
 * successful CAS login page rather than as an HTTP error. Treating that page as
 * business HTML causes confusing empty screens; this guard converts it into a
 * clear re-login failure.
 *
 * 重要：本 guard **不再**直接调 [SessionEvents.notifyExpired]。原因是 reauth-retry
 * 流程（见 [JwcClient.runWithSessionRecovery]）希望在尝试静默重登后再决定要不要
 * 把用户踢回登录页。如果这里就广播 expired，会和 retry 成功后的 `_state = LoggedIn`
 * 发生竞态闪烁。会话过期信号统一由上层 [JwcClient] 在 retry 也失败时再发。
 */
object JwcResponseGuard {
    fun readJwcHtml(response: Response, emptyMessage: String): String {
        validateJwcResponse(response)
        val body = response.body.string()
        if (body.isBlank()) throw JwcException(JwcError.EmptyResponse, emptyMessage)
        // 落点 host 在 jwc 但内容是登录表单（200 渲染 / Vue SPA 客户端跳转 / Portal 登录页）—— 嗅探
        if (looksLikeLoginPage(body)) {
            throw JwcException(JwcError.SessionExpired)
        }
        return body
    }

    fun validateJwcResponse(response: Response) {
        val finalUrl = response.request.url
        val finalHost = finalUrl.host
        val finalPath = finalUrl.encodedPath

        // CAS 显式踢回登录页
        if (finalHost == JxnuUrls.CAS_HOST && finalPath.startsWith("/cas/login")) {
            throw JwcException(JwcError.SessionExpired)
        }
        // jwc 内部的 SSO 重登页：会话失效但还没走到 CAS 这条路时落点
        if (finalHost == JxnuUrls.JWC_HOST && finalPath.startsWith("/Portal/LoginAccount.aspx")) {
            throw JwcException(JwcError.SessionExpired)
        }
        if (finalHost != JxnuUrls.JWC_HOST) {
            throw JwcException(JwcError.UnexpectedRedirect(finalHost))
        }
        if (!response.isSuccessful) {
            throw JwcException(JwcError.Server(response.code))
        }
    }

    /**
     * 内容嗅探：fingerprint 来自 CAS / Portal 登录表单都会出现的字段。
     * 只看 HTML 头 8KB 即可——登录页 fingerprint 都在文档头部，且全文 scan 太贵。
     */
    private val LOGIN_PAGE_FINGERPRINTS = listOf(
        "__RSA__",
        "name=\"passwordEncrypt\"",
        "id=\"loginForm\"",
        "name=\"execution\"",
        "/cas/login?service",
    )

    private fun looksLikeLoginPage(body: String): Boolean {
        val head = if (body.length > 8192) body.substring(0, 8192) else body
        return LOGIN_PAGE_FINGERPRINTS.any { it in head }
    }
}
