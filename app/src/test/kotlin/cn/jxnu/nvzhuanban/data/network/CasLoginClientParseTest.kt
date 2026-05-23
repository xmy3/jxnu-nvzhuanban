package cn.jxnu.nvzhuanban.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `CasLoginClient.parseExecutionFromHtml` 的解析回归测试。
 *
 * 这里测的是「拿到 CAS 登录页 HTML 后能否抽到 execution」这一步，不是网络 / cookie 行为。
 *
 * 历史问题：曾间歇性报 "登录页结构异常：缺少 execution 字段"。根因是 CAS TGC cookie 没清导致
 * 第一步 GET 落到非登录页，而非解析逻辑本身有 bug。CAS cookie 清理在 [CasLoginClient.login] 中完成，
 * 那段逻辑依赖真实 OkHttp，没法在 unit test 层覆盖；这里至少把解析路径锁死。
 */
class CasLoginClientParseTest {

    @Test
    fun `extracts execution from hidden input`() {
        val html = """
            <html><body>
            <form action="/cas/login">
              <input type="hidden" name="execution" value="e1s1">
              <input type="text" name="username">
            </form>
            </body></html>
        """.trimIndent()
        assertEquals("e1s1", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `extracts execution from inline JS object (single quotes)`() {
        // 江师大 Vue 单页常见形态：window.__INITIAL_STATE__ = { execution: 'e2s3', ... }
        val html = """
            <html><body>
            <script>window.__INITIAL_STATE__ = { execution: 'e2s3', flow: 'login' };</script>
            </body></html>
        """.trimIndent()
        assertEquals("e2s3", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `extracts execution from inline JS object (double quotes)`() {
        val html = """
            <html><body>
            <script>var s = {"execution":"e7s9","loginUrl":"/cas/login"};</script>
            </body></html>
        """.trimIndent()
        assertEquals("e7s9", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `prefers hidden input over JS when both present`() {
        // 旧版 CAS 同时双兼容时，input 是权威来源（JS 可能是 SSR 缓存）
        val html = """
            <html><body>
            <input type="hidden" name="execution" value="from-input">
            <script>var s = { execution: "from-js" };</script>
            </body></html>
        """.trimIndent()
        assertEquals("from-input", CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `returns null when execution missing entirely`() {
        // 模拟 CAS 直接 302 跳过登录页后落到了 jwc 主页：HTML 里根本没有 execution。
        val html = """
            <html><body>
            <h1>江西师范大学教务在线</h1>
            <a href="/User/Default.aspx">个人中心</a>
            </body></html>
        """.trimIndent()
        assertNull(CasLoginClient.parseExecutionFromHtml(html))
    }

    @Test
    fun `returns null when execution input has empty value`() {
        // 空 value 不应被当成有效 token——继续尝试 JS 兜底
        val html = """
            <html><body>
            <input type="hidden" name="execution" value="">
            </body></html>
        """.trimIndent()
        assertNull(CasLoginClient.parseExecutionFromHtml(html))
    }
}
