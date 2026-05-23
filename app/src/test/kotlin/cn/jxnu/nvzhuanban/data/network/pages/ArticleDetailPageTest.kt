package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.InlineRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知详情页 parser 回归测试。
 *
 * Fixture: `app/src/test/resources/samples/article_detail.html` —— 仿真一篇 jwc 通知，
 * 标题 / 时间 / 多段正文 / 加粗 / 内联颜色 / 表格 / 图片 / 附件 / 外链 / `javascript:` 都各出现一次。
 *
 * baseUri 取 `https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=99999`，
 * 用来验证相对路径 `../files/...` → `https://jwc.jxnu.edu.cn/files/...` 的 abs URL 解析。
 */
class ArticleDetailPageTest {

    private val baseUri = "https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=99999"

    @Test
    fun `parses title and posted time stripped of bracket wrapper`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)

        assertEquals("关于2026年春季学期期末考试安排的通知", parsed.title)
        // 「【时间：YYYY-MM-DD HH:mm:ss】」外壳应被剥掉
        assertEquals("2026-05-22 16:28:25", parsed.postedAt)
    }

    @Test
    fun `parses body into expected block sequence`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)

        // 至少应包含：段落 / 表格 / 段落 / 段落 / 图片 / 分割线 / 落款段落
        val kinds = parsed.blocks.map { it::class.simpleName }
        assertTrue("缺少 Paragraph: $kinds", "Paragraph" in kinds)
        assertTrue("缺少 Table: $kinds", "Table" in kinds)
        assertTrue("缺少 Attachment: $kinds", "Attachment" in kinds)
        assertTrue("缺少 Image: $kinds", "Image" in kinds)
        assertTrue("缺少 Divider: $kinds", "Divider" in kinds)
    }

    @Test
    fun `paragraph keeps inline bold and colored runs`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()

        val withBold = paragraphs.firstOrNull { p ->
            p.runs.any { it is InlineRun.Text && it.text.contains("期末考试") && it.style.bold }
        }
        assertNotNull("应有一段含『期末考试』的加粗 Text run", withBold)

        val withRed = paragraphs.firstOrNull { p ->
            p.runs.any { it is InlineRun.Text && it.text.contains("第18周") && it.style.color != null }
        }
        assertNotNull("应有一段含『第18周』的着色 Text run", withRed)
    }

    @Test
    fun `external https link becomes clickable Link, javascript anchor degrades to text`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val paragraphs = parsed.blocks.filterIsInstance<ArticleBlock.Paragraph>()
        val allLinks = paragraphs.flatMap { it.runs }.filterIsInstance<InlineRun.Link>()

        // 教育部考试政策网应作为 Link
        val externalLink = allLinks.firstOrNull { it.url == "https://www.example.com/policy" }
        assertNotNull("外链未识别为 Link: $allLinks", externalLink)
        assertEquals("教育部考试政策网", externalLink!!.text)

        // javascript: 链接不应进 Link
        assertTrue(
            "javascript: 链接被错误识别为 Link: $allLinks",
            allLinks.none { it.url.startsWith("javascript", ignoreCase = true) },
        )
    }

    @Test
    fun `attachment with pdf extension is extracted as Attachment block`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val attachment = parsed.blocks.filterIsInstance<ArticleBlock.Attachment>().firstOrNull()

        assertNotNull("应识别出 PDF 附件", attachment)
        assertEquals("2026春季期末考试安排表.pdf", attachment!!.name)
        // baseUri 解析后变绝对 URL
        assertEquals("https://jwc.jxnu.edu.cn/files/exam_2026_spring.pdf", attachment.url)
    }

    @Test
    fun `image src resolves to absolute url via baseUri`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val img = parsed.blocks.filterIsInstance<ArticleBlock.Image>().firstOrNull()

        assertNotNull("应解析出图片块", img)
        assertEquals("https://jwc.jxnu.edu.cn/files/exam_room_map.jpg", img!!.src)
        assertEquals("考场分布图", img.alt)
    }

    @Test
    fun `table extracts header + data rows`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val table = parsed.blocks.filterIsInstance<ArticleBlock.Table>().firstOrNull()

        assertNotNull("应解析出表格块", table)
        assertEquals("3 行（含表头）", 3, table!!.rows.size)
        assertEquals(3, table.rows[0].size)  // 课程 / 时间 / 地点
        // 表头第一格应是「课程」
        val headerFirstCell = table.rows[0][0]
        assertEquals(
            "课程",
            headerFirstCell.filterIsInstance<InlineRun.Text>().joinToString("") { it.text }.trim(),
        )
        // 数据行第一行第一格「高等数学」(<td><p>...</p></td> Word-wrap 形式)
        val firstRow = table.rows[1][0]
        assertEquals(
            "高等数学",
            firstRow.filterIsInstance<InlineRun.Text>().joinToString("") { it.text }.trim(),
        )
    }

    /**
     * 回归：jwc 通知的表格单元格经常是 Word 复制粘贴出来的 `<td><p>x</p></td>` 或
     * `<td><div>x</div></td>`，曾经因为 cell walker 内部 flushParagraph 把文本踢去
     * 不被读取的 blocks 列表，导致单元格在 UI 上全空白（只剩表格框）。
     */
    @Test
    fun `table cells with nested p or div still extract their text`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val table = parsed.blocks.filterIsInstance<ArticleBlock.Table>().firstOrNull()
        assertNotNull(table)

        fun flatten(runs: List<InlineRun>): String = runs.joinToString("") { r ->
            when (r) {
                is InlineRun.Text -> r.text
                is InlineRun.Link -> r.text
                InlineRun.LineBreak -> "\n"
            }
        }.trim()

        // 第 2 行（<td><p>...</p></td>）
        assertEquals("高等数学", flatten(table!!.rows[1][0]))
        assertEquals("6月22日 上午", flatten(table.rows[1][1]))
        assertEquals("文科楼A301", flatten(table.rows[1][2]))

        // 第 3 行（<td><div>...</div></td>，且第二格还带 <br/>）
        assertEquals("大学英语", flatten(table.rows[2][0]))
        assertEquals("6月23日\n下午", flatten(table.rows[2][1]))
        assertEquals("文科楼A302", flatten(table.rows[2][2]))
    }

    @Test
    fun `returns empty ArticleDetail when no main-content`() {
        val parsed = ArticleDetailPage.parse("<html><body><p>hi</p></body></html>", baseUri)
        assertEquals("", parsed.title)
        assertEquals("", parsed.postedAt)
        assertTrue(parsed.blocks.isEmpty())
    }

    @Test
    fun `null-tolerant when inner main-content is missing`() {
        // 仅外层包壳，内层缺失（404 / 未登录页接近这种形状）
        val html = """
            <html><body>
              <div id="main-content" class="line padding-big-bottom">
                <div class="text-large border-bottom padding text-center">文档不存在！</div>
                <div class="line text-sub text-center"></div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = ArticleDetailPage.parse(html, baseUri)
        assertEquals("文档不存在！", parsed.title)
        assertEquals("", parsed.postedAt)
        assertTrue(parsed.blocks.isEmpty())
    }

    @Test
    fun `does not surface print or close buttons as blocks`() {
        val parsed = ArticleDetailPage.parse(sampleHtml("article_detail.html"), baseUri)
        val flatText = parsed.blocks
            .filterIsInstance<ArticleBlock.Paragraph>()
            .joinToString("\n") { p ->
                p.runs.joinToString("") {
                    when (it) {
                        is InlineRun.Text -> it.text
                        is InlineRun.Link -> it.text
                        InlineRun.LineBreak -> "\n"
                    }
                }
            }
        // 「关闭窗口」位于外层 .border-top 容器里，不在内层 #main-content
        assertNull("『关闭窗口』被错误纳入正文", flatText.lineSequence().firstOrNull { "关闭窗口" in it })
    }
}
