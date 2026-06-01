package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.PLACEHOLDER_USER_NAME
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.network.JwcClient
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * 解析 `/User/Default.aspx`（学生工作台）页面。
 *
 * 教务首页只有 `<span id="lblUserInfor">   欢迎您，(202526202038,Student) 邹全</span>` 这一行
 * 用户信息，**没有学院/专业/班级**。所以这里只负责拿姓名+学号，剩下的字段交给 Profile 模块
 * 用 `GradeRepository` 兜底（成绩页的 `_ctl6_lblMsg` 有完整 meta）。
 */
object UserDefaultPage {

    /**
     * `lblUserInfor` 文本形如：`   欢迎您，(202526202038,Student) 邹全`
     * - 括号内是 `学号,角色`
     * - 括号后是姓名
     *
     * 兼容点：
     *  - `，` / `,` 半角全角都吃
     *  - `()` / `（）` 半角全角都吃（教务网偶尔出现全角括号，尤其换主题时）
     *  - 姓名收紧到 2-10 个中文字符或 `·`，避免吃到后面同级 span 渲染出的"在线"等噪声
     */
    private val WELCOME = Regex(
        """欢迎您[，,]\s*[（(]\s*([^,，)）\s]+)\s*[,，]\s*[A-Za-z]+\s*[）)]\s*([一-龥·]{2,10})"""
    )

    /**
     * GET `/User/Default.aspx` 拿 HTML 并解析。
     *
     * 故意不用 `getHtmlAuth` 变体：调用方 [cn.jxnu.nvzhuanban.data.repository.AuthRepository]
     * 本身就是 reauth 流程，再走 `runWithSessionRecovery` 会出现"recovery 套 recovery"
     * 死循环 —— 例如 cookie 失效 → reauth → 拉用户首页 → 又触发 SessionExpired → 又 reauth。
     *
     * 异常处理：故意**不**在这里 `runCatching` 吞掉，让网络/解析错误向上抛。
     * `AuthRepository` 的两处调用点都已经 `runCatching { fetchAndParse(...) }.getOrNull()
     * ?: parse(username, "")` 兜底，由它判断"是真的没拿到 → 退化到学号占位 + name=同学"
     * 还是"网络瞬时失败 → 用户重启后再试"。这里若 silent `.orEmpty()` 反而会让上层失去信号、
     * 永远走占位分支，掩盖真实问题（曾经的 bug：CAS 流程 200 返回登录页时被当成"用户首页空响应"
     * 而非"会话失效"）。
     */
    suspend fun fetchAndParse(studentId: String): UserProfile = withContext(Dispatchers.IO) {
        val html = JwcClient.getHtml(JxnuUrls.USER_DEFAULT, "用户首页返回空响应")
        parse(studentId, html)
    }

    internal fun parse(studentId: String, html: String): UserProfile {
        // 优先用 HTML 里 lblUserInfor 实际渲染出的学号 —— 调用方传入的 studentId 可能是空串
        // （cookie 有效但本地没记账号的场景），此时仍能从 HTML 里把学号补出来。
        val (parsedStudentId, parsedName) = extractIdAndName(html)
        val resolvedId = studentId.ifBlank { parsedStudentId.orEmpty() }
        val grade = inferGradeFromStudentId(resolvedId)
        val name = parsedName?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_USER_NAME
        return UserProfile(
            studentId = resolvedId,
            name = name,
            college = "",
            major = "",
            className = "",
            grade = grade,
            avatarUrl = if (resolvedId.isNotBlank()) JxnuUrls.userPhotoUrl(resolvedId) else null,
        )
    }

    /** 学号前 4 位通常是入学年份，例如 `202526202038` → 2025 级。 */
    private fun inferGradeFromStudentId(studentId: String): Int {
        return studentId.take(4).toIntOrNull() ?: 0
    }

    /**
     * 从 `欢迎您，(学号,Student) 姓名` 中抓 (学号, 姓名)。任何一段抓不到返回 null。
     */
    private fun extractIdAndName(html: String): Pair<String?, String?> {
        if (html.isBlank()) return null to null
        val text = runCatching { Jsoup.parse(html).selectFirst("#lblUserInfor")?.text() }
            .getOrNull()
            ?: html
        val m = WELCOME.find(text) ?: return null to null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() } to
            m.groupValues[2].trim().takeIf { it.isNotEmpty() }
    }
}
