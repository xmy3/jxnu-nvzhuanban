package cn.jxnu.nvzhuanban.data.network.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户首页 (User/Default.aspx) 解析回归测试。
 *
 * 只测纯解析逻辑 [UserDefaultPage.parse]（internal 但同模块测试可见）。
 *
 * 关键覆盖：
 *   - `<span id="lblUserInfor">` 内 `欢迎您，(学号,Student) 姓名` 文本抽取
 *   - 学号前 4 位推断入学年级
 *   - 学院/专业/班级在首页拿不到 → 留空
 *   - 头像 URL 应携带 base64 编码的学号（jwc 教务头像约定）
 *   - lblUserInfor 缺失时 name 兜底为 "同学"
 */
class UserDefaultPageTest {

    @Test
    fun `extracts name from lblUserInfor welcome text`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_NORMAL)
        assertEquals("张三", profile.name)
        assertEquals("20250101", profile.studentId)
        assertEquals(2025, profile.grade)
        assertEquals("", profile.college)
        assertEquals("", profile.major)
        assertEquals("", profile.className)
    }

    @Test
    fun `tolerates extra whitespace and fullwidth nbsp`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_WITH_NBSP)
        assertEquals("李四", profile.name)
    }

    @Test
    fun `falls back to default name when lblUserInfor missing`() {
        val profile = UserDefaultPage.parse("20240101", "<html><body><p>nothing</p></body></html>")
        assertEquals("同学", profile.name)
        assertEquals(2024, profile.grade)
    }

    @Test
    fun `falls back to default name when html empty`() {
        val profile = UserDefaultPage.parse("20240101", "")
        assertEquals("同学", profile.name)
    }

    @Test
    fun `grade is zero when studentId not numeric prefix`() {
        val profile = UserDefaultPage.parse("BADID", FIXTURE_NORMAL)
        assertEquals(0, profile.grade)
    }

    @Test
    fun `generates a photo url that points at jwc PhotoShow endpoint`() {
        val profile = UserDefaultPage.parse("20250101", FIXTURE_NORMAL)
        assertTrue("应当生成头像 URL", !profile.avatarUrl.isNullOrBlank())
        assertTrue(
            "avatarUrl 应指向 All_PhotoShow 端点: ${profile.avatarUrl}",
            profile.avatarUrl!!.contains("All_PhotoShow.aspx"),
        )
        assertTrue(
            "avatarUrl 应携带 base64 编码后的 UserNum: ${profile.avatarUrl}",
            profile.avatarUrl!!.contains("UserNum=MjAyNTAxMDE%3D"),
        )
    }

    private companion object {
        val FIXTURE_NORMAL = """
            <html><body>
            <span id="lblUserInfor">   欢迎您，(20250101,Student) 张三</span>
            </body></html>
        """.trimIndent()

        // 教务真实页里 `欢迎您，` 后常有多余的全角/半角空格
        val FIXTURE_WITH_NBSP = """
            <html><body>
            <span id="lblUserInfor">&nbsp;&nbsp;欢迎您， ( 20250101 , Student )  李四</span>
            </body></html>
        """.trimIndent()
    }
}
