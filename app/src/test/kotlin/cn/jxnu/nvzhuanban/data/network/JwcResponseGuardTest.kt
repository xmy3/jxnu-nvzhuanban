package cn.jxnu.nvzhuanban.data.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JwcResponseGuardTest {

    @Test
    fun `reads html from successful jwc response`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, "<html>ok</html>")

        assertEquals("<html>ok</html>", JwcResponseGuard.readJwcHtml(response, "empty"))
    }

    @Test
    fun `throws session expired when final url is cas login`() {
        val response = response("https://uis.jxnu.edu.cn/cas/login?service=x", 200, "<form></form>")
        // 新契约：guard **不**再直接 notify SessionEvents，把这一职责留给 JwcClient.runWithSessionRecovery，
        // 这样 reauth 成功的场景就不会先广播 expired 再撤回，造成 UI 闪烁。
        val before = SessionEvents.expiredSignal.value

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
        assertEquals("登录已过期，请重新登录", thrown.toUserMessage())
        assertEquals(before, SessionEvents.expiredSignal.value)
    }

    @Test
    fun `throws session expired when final url is jwc portal login account`() {
        val response = response("https://jwc.jxnu.edu.cn/Portal/LoginAccount.aspx?t=sso", 200, "<html></html>")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `throws session expired when body sniffs as cas login form`() {
        // jwc host 自身回了一个登录表单 HTML（200 渲染 / SPA 客户端跳转 / Portal 钓鱼回流）。
        // 嗅探到 __RSA__ 关键字应当强制视为会话过期，避免业务解析器吃到空数据后展示空白页。
        val body = """
            <html><body>
              <form id="loginForm" action="/cas/login?service=...">
                <input type="hidden" name="passwordEncrypt" value="__RSA__abc">
              </form>
            </body></html>
        """.trimIndent()
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, body)

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.SessionExpired, thrown.error)
    }

    @Test
    fun `rejects unexpected final host`() {
        val response = response("https://example.com/phishing", 200, "<html></html>")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.UnexpectedRedirect("example.com"), thrown.error)
    }

    @Test
    fun `rejects unsuccessful jwc response`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 500, "boom")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.Server(500), thrown.error)
    }

    @Test
    fun `rejects empty jwc response with structured error`() {
        val response = response("https://jwc.jxnu.edu.cn/User/Default.aspx", 200, "")

        val thrown = assertThrows(JwcException::class.java) {
            JwcResponseGuard.readJwcHtml(response, "empty")
        }
        assertEquals(JwcError.EmptyResponse, thrown.error)
    }

    private fun response(url: String, code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Server Error")
            .body(body.toResponseBody())
            .build()
}
