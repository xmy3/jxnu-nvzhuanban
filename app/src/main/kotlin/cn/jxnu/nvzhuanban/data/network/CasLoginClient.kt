package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * CAS SSO 登录流程（江西师大教务系统专用）。
 *
 * 完整 5 步：
 *  1. GET `/cas/login?service=...` 拉登录页 HTML，解出 `execution` token
 *  2. GET `/cas/jwt/publicKey` 拉 RSA 公钥
 *  3. 用公钥加密密码 → `__RSA__<base64>`
 *  4. POST `/cas/login?service=...` 表单（含 username、加密密码、execution、fpVisitorId 等 13 个字段）
 *  5. OkHttp 自动跟随 302 → SSO 验票 → 落 `/User/Default.aspx`
 *
 * 判断登录是否成功：看最终重定向落点的 URL。
 *  - 落 `jwc.jxnu.edu.cn/User/...` 或 `/Portal/...` ⇒ 成功
 *  - 落 `uis.jxnu.edu.cn/cas/login` ⇒ 失败（账号密码错、需验证码、需 MFA 等），从 HTML 抓错误信息
 */
class CasLoginClient(
    context: Context,
    private val httpClient: JxnuHttpClient = JxnuHttpClient.get(),
    private val authStorage: AuthStorage = AuthStorage.get(),
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data object Success : Result
        /**
         * [isAuth] = true 表示这是 CAS 明确拒绝认证（账号密码错 / 锁定 / 需 MFA 等），
         * 上层据此判断"凭证已失效，应清掉本地保存的密码"。
         * 默认 false 涵盖网络异常、教务网 5xx、HTML 结构异常等瞬时/未知失败 ——
         * 这种情况下不能清凭证（否则用户切到差网络一次就丢密码）。
         */
        data class Failure(val message: String, val isAuth: Boolean = false) : Result
    }

    /** [fetchExecutionToken] 的细分结果，便于区分网络失败 / 重定向 / HTML 结构异常。 */
    private sealed interface ExecutionResult {
        data class Ok(val token: String) : ExecutionResult
        data class Bad(val message: String) : ExecutionResult
    }

    suspend fun login(
        username: String,
        password: String,
        captcha: String = "",
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            // 起手清掉 uis.jxnu.edu.cn 域的旧 cookie（主要是 CASTGC / TGC）。
            //
            // 历史 bug：曾有一次报"登录页结构异常：缺少 execution 字段"，间歇出现无法稳定复现。
            // 根因是 jwc 业务 session 失效后 validateSession 已返回 false，但 CAS 的 TGC 还有效——
            // GET 登录页时 CAS 会直接 302 跳过登录表单走 SSO，OkHttp 跟随重定向落在 jwc 主页，
            // 解析出的 HTML 根本没有 execution 字段。
            //
            // 这里清掉 uis 域 cookie，迫使 CAS 重新渲染登录表单。原代码注释里作者预判过此问题
            // （"如果遇到问题再加 clear"），现在补上。
            httpClient.cookieJar.clearForHost(JxnuUrls.CAS_HOST)

            val loginEntryUrl = JxnuUrls.casLoginEntry()
            val execution = when (val r = fetchExecutionToken(loginEntryUrl)) {
                is ExecutionResult.Ok -> r.token
                is ExecutionResult.Bad -> return@runCatching Result.Failure(r.message)
            }

            val publicKeyMaterial = fetchPublicKey()
            val encryptedPassword = runCatching {
                RsaPasswordEncryptor.encryptPassword(password, publicKeyMaterial)
            }.getOrElse {
                return@runCatching Result.Failure("密码加密失败：${it.message}")
            }

            val visitorId = DeviceFingerprint.visitorId(appContext, authStorage)

            val form = FormBody.Builder()
                .add("username", username)
                .add("password", encryptedPassword)
                .add("captcha", captcha)
                .add("rememberMe", "on")
                .add("currentMenu", "1")
                .add("failN", "-1")
                .add("mfaState", "")
                .add("execution", execution)
                .add("_eventId", "submit")
                .add("geolocation", "")
                .add("fpVisitorId", visitorId)
                .add("trustAgent", "")
                .add("submit1", "Login1")
                .build()

            val req = Request.Builder()
                .url(loginEntryUrl)
                .post(form)
                .header("Referer", loginEntryUrl)
                .header("Origin", "https://${JxnuUrls.CAS_HOST}")
                .build()

            httpClient.client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val finalHost = resp.request.url.host
                when {
                    !resp.isSuccessful && resp.code !in 300..399 ->
                        Result.Failure("登录失败：HTTP ${resp.code}")

                    finalHost == JxnuUrls.JWC_HOST -> Result.Success

                    finalHost == JxnuUrls.CAS_HOST && finalUrl.contains("/cas/login") -> {
                        val body = resp.body.string()
                        val errorMessage = parseCasError(body) ?: "账号或密码错误"
                        Result.Failure(errorMessage, isAuth = true)
                    }

                    else -> Result.Failure("登录后落点未识别：$finalUrl")
                }
            }
        }.getOrElse { e ->
            Result.Failure(formatException(e))
        }
    }

    /** 探测当前 cookie 是否还能登录：尝试 GET /User/Default.aspx，看是否被踢回 CAS 登录页。 */
    suspend fun validateSession(): Boolean = withContext(Dispatchers.IO) {
        if (!httpClient.cookieJar.hasJwcSession()) return@withContext false
        runCatching {
            val req = Request.Builder().url(JxnuUrls.USER_DEFAULT).get().build()
            httpClient.client.newCall(req).execute().use { resp ->
                // 复用业务页守卫：HTTP 200 但实为登录页 / 跳 CAS / 跳 SSO 重登页都会抛，
                // 不抛才算真正的已登录工作台。旧实现只看「200 + 落点在 jwc host」，会把
                // 「200 渲染的登录页」当成有效会话放行，随后 fetchAndParse 又被同一守卫判
                // SessionExpired 而降级 → 姓名退化「同学」。两处现在同判定，不再撕裂。
                JwcResponseGuard.readJwcHtml(resp, "用户首页返回空响应")
                true
            }
        }.getOrDefault(false)
    }

    /** 退出：清 cookie + 清存储；下一次访问需要重新登录。 */
    fun logout() {
        httpClient.cookieJar.clearAll()
        // 不清 lastUsername / installUuid，保留用户体验
    }

    @Throws(IOException::class)
    private fun fetchExecutionToken(loginUrl: String): ExecutionResult {
        val req = Request.Builder().url(loginUrl).get().build()
        httpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ExecutionResult.Bad("登录页请求失败：HTTP ${resp.code}")
            }
            // OkHttp 跟随重定向后落点要么仍在 CAS（正常的登录表单），要么被 SSO 直接跳到了
            // jwc / Portal（说明 CAS 视角已认证，本不该走到 login 流程）。后者通常意味着
            // 上面 clearForHost 没生效或之后又被刷上了新 cookie——抛出明确信息便于排查。
            val finalHost = resp.request.url.host
            if (finalHost != JxnuUrls.CAS_HOST) {
                return ExecutionResult.Bad("登录页被重定向至 $finalHost，CAS 可能已存在有效会话")
            }
            val html = resp.body.string()
            if (html.isEmpty()) {
                return ExecutionResult.Bad("登录页响应为空")
            }
            return parseExecutionFromHtml(html)
                ?.let { ExecutionResult.Ok(it) }
                ?: ExecutionResult.Bad("登录页结构异常：未找到 execution 字段（CAS 可能已改版）")
        }
    }

    internal companion object {
        /**
         * 从 CAS 登录页 HTML 中提取 `execution` 字段，提不到返回 null。Pure function，便于单测。
         *
         * 兼容两种渲染：
         *  - 传统 CAS 表单：`<input type="hidden" name="execution" value="e1s1">`
         *  - Vue 单页（江西师大现行版）：内联 JS 里 `execution: "..."` 或 `"execution":"..."`
         */
        internal fun parseExecutionFromHtml(html: String): String? {
            val doc = Jsoup.parse(html)
            doc.selectFirst("input[name=execution]")?.attr("value")?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            val regex = Regex("""["']?execution["']?\s*[:=]\s*["']([^"']+)["']""")
            return regex.find(html)?.groupValues?.get(1)
        }
    }

    @Throws(IOException::class)
    private fun fetchPublicKey(): String {
        val req = Request.Builder()
            .url(JxnuUrls.CAS_PUBLIC_KEY)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", JxnuUrls.casLoginEntry())
            .build()
        httpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("公钥接口返回 HTTP ${resp.code}")
            return resp.body.string().trim().also {
                if (it.isEmpty()) error("公钥响应为空")
            }
        }
    }

    private fun parseCasError(html: String): String? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        // CAS 错误通常落在 .alert / .error-msg / [data-v-*].msg 之类的节点
        val candidates = listOf(
            "div.alert-danger",
            "div.error-msg",
            "div.errors",
            "div[class*=error]",
            "p.error",
            "span.error",
        )
        for (sel in candidates) {
            val text = doc.selectFirst(sel)?.text()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        // 江西师大 CAS 用 Vue 渲染错误提示，可能藏在 data-* 或 JS 里。
        // 旧 regex 还匹配了 `msg` / `message` 这种通用字段名，CAS 错误页之外的 CSS/JS 也会撞到，
        // 容易把无关字符串当成"错误信息"展示。这里收紧到 CAS 真正用的 errorMsg / errorMessage / errMsg。
        val jsErr = Regex("""\b(?:errorMsg|errorMessage|errMsg)\s*[:=]\s*["']([^"']{4,80})["']""")
            .find(html)?.groupValues?.get(1)
        if (!jsErr.isNullOrEmpty()) return jsErr
        return null
    }

    private fun formatException(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络连接"
        is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
        is javax.net.ssl.SSLException -> "SSL 握手失败：${t.message ?: "证书错误"}"
        is IOException -> "网络异常：${t.message ?: "I/O 错误"}"
        else -> t.message ?: t::class.java.simpleName
    }
}
