package cn.jxnu.nvzhuanban.data.network.pages

import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.InlineStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 教务通知详情页解析器：`Portal/ArticlesView.aspx?id=<id>`。
 *
 * 页面结构（外层 jwc 模板已省略）：
 * ```html
 * <div id="main-content" class="line padding-big-bottom">         <-- 外层
 *   <div class="text-large border-bottom padding text-center">标题</div>
 *   <div class="line text-sub text-center">【时间：YYYY-MM-DD HH:mm:ss】</div>
 *   <div id="main-content" class="line padding">…正文 HTML…</div>   <-- 内层（重复的 id）
 *   <div class="line text-right border-top margin padding">…打印/关闭…</div>
 * </div>
 * ```
 *
 * 关键点：
 * - `#main-content` id **重复出现**两次。外层是包壳，内层才是正文。用 `outer.children` 显式找内层，
 *   避免 `selectFirst` 命中外层自身。
 * - 必须给 [Jsoup.parse] 传 baseUri（请求 URL），否则 `<img src="../files/x.jpg">` / 相对 `<a href>`
 *   解析出来都是空字符串。
 * - jwc 详情页是 Word 复制粘贴出身，DOM 里全是 `<div>` 包 `<div>` + 内联 `<span style>`。解析时把
 *   "块级容器无块级子节点"视作一个段落，"块级容器有块级子节点"则递归下钻；这样能把 Word 嵌套层级
 *   摊平到 Compose LazyColumn 可消费的扁平 [ArticleBlock] 序列。
 */
object ArticleDetailPage {

    private val ATTACHMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "rar", "7z", "txt", "csv",
    )

    /**
     * 教务网时间外壳：「【时间：YYYY-MM-DD HH:mm:ss】」。冒号兼容中英文。
     * 抓不到时回退到原文 trim（部分历史 fixture 没带这层壳）。
     */
    private val POSTED_REGEX = Regex("""【\s*时间\s*[:：]\s*(.+?)\s*】""")

    /**
     * jwc 无会话时 `ArticlesView.aspx` 返回的占位页标题特征：
     * 「对不起，该文档需要登录后再查看!」。命中后整页按"需要登录"处理，不再把页内那条
     * 指向网页登录的「登录」超链接渲染成外链（避免点击跳出 app 去官方教务处网页）。
     * 真实通知标题不会出现这些短语，误判风险可忽略。
     */
    private val LOGIN_REQUIRED_TITLE = Regex("""需要登录|登录后再?查看|请登录""")

    /** `<font color>` / `style="color:..."` 共用：六位 hex（带或不带 #），或最常见的英文色名。 */
    private val HEX_COLOR = Regex("""#?([0-9a-fA-F]{6})""")
    private val NAMED_COLORS = mapOf(
        "red" to 0xFFFF0000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "green" to 0xFF008000.toInt(),
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
    )

    /** `style="color:#abcdef; font-weight: bold"` 里抽 color。其他样式当前不支持。 */
    private val STYLE_COLOR = Regex("""(?i)color\s*:\s*([^;]+)""")

    /** 块级 HTML 标签集合，用于判断一个容器内是否有块级子元素。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "blockquote",
        "table", "thead", "tbody", "tr", "td", "th",
        "ul", "ol", "li", "hr", "img",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    fun parse(html: String, baseUri: String): ArticleDetail {
        val doc = Jsoup.parse(html, baseUri)
        // 教务网每个详情页都有 form#aspnetForm > div.main-container > div#main-content（外层），
        // selectFirst 命中的就是这个外壳。
        val outer = doc.selectFirst("div#main-content")
            ?: return ArticleDetail(title = "", postedAt = "", blocks = emptyList())

        val title = outer.selectFirst(".text-large.border-bottom")?.text()?.trim().orEmpty()
        val dateRaw = outer.selectFirst(".text-sub.text-center")?.text().orEmpty()
        val postedAt = POSTED_REGEX.find(dateRaw)?.groupValues?.get(1)?.trim()
            ?: dateRaw.trim()

        // 未登录占位页：标题即「…需要登录后再查看!」。短路返回，blocks 置空，交给 UI 引导内部登录。
        if (LOGIN_REQUIRED_TITLE.containsMatchIn(title)) {
            return ArticleDetail(title, postedAt, blocks = emptyList(), requiresLogin = true)
        }

        // 显式从外层 children 里找内层 #main-content，不用 selectFirst 防止把外层自己拿回来
        val body = outer.children().firstOrNull { it.id() == "main-content" }
            ?: return ArticleDetail(title, postedAt, emptyList())

        val blocks = BodyWalker().walk(body)
        return ArticleDetail(title, postedAt, blocks)
    }

    /**
     * 递归把 jwc 内层 #main-content 的子树摊平成 [ArticleBlock] 列表。
     *
     * 设计要点：
     * - 累加器模式：内联 runs 收在 [pendingRuns] 里，碰到块级 boundary（`<p>` / `<div>` 关闭、
     *   `<img>` / `<table>` / `<hr>` 出现）就 flush 成一个 [ArticleBlock.Paragraph]。
     * - 样式继承：`<b>` / `<strong>` / `<i>` / `<em>` / `<u>` / `<font color>` / `style="color:"`
     *   通过参数 [InlineStyle] 传递；离开 element 时自动恢复（依赖 Kotlin 函数栈，不需要显式 push/pop）。
     * - 表格特殊化：[parseTable] 用 [BodyWalker] 子实例隔离 pendingRuns，避免单元格内的 runs
     *   污染外层段落状态。**单元格用 `inlineOnly = true` 实例**——Word 复制粘贴的 HTML 经常把
     *   单元格内容包在 `<p>` / `<div>` 里，原本的 flushParagraph 会把这些文本踢去 `blocks`，而
     *   cell walker 不读 blocks → 单元格全空。inlineOnly 模式把 flush 改成插 LineBreak，文本
     *   留在 pendingRuns 里被 collectCellInline 收回。
     */
    private class BodyWalker(private val inlineOnly: Boolean = false) {
        private val blocks = mutableListOf<ArticleBlock>()
        private val pendingRuns = mutableListOf<InlineRun>()

        fun walk(body: Element): List<ArticleBlock> {
            walkChildren(body, InlineStyle())
            flushParagraph()
            return blocks.toList()
        }

        private fun walkChildren(element: Element, style: InlineStyle) {
            for (node in element.childNodes()) walkNode(node, style)
        }

        private fun walkNode(node: Node, style: InlineStyle) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotEmpty()) pendingRuns += InlineRun.Text(text, style)
                }
                is Element -> walkElement(node, style)
                else -> Unit
            }
        }

        private fun walkElement(el: Element, style: InlineStyle) {
            when (el.tagName().lowercase()) {
                "img" -> {
                    flushParagraph()
                    val src = el.absUrl("src")
                    if (src.isNotBlank()) {
                        blocks += ArticleBlock.Image(
                            src = src,
                            alt = el.attr("alt").takeIf { it.isNotBlank() },
                        )
                    }
                }
                "table" -> {
                    flushParagraph()
                    val rows = parseTable(el)
                    if (rows.isNotEmpty()) blocks += ArticleBlock.Table(rows)
                }
                "hr" -> {
                    flushParagraph()
                    blocks += ArticleBlock.Divider
                }
                "br" -> pendingRuns += InlineRun.LineBreak

                "a" -> {
                    val href = el.absUrl("href").trim()
                    if (isAttachment(href)) {
                        flushParagraph()
                        val name = el.text().trim().ifBlank { hrefFileName(href) }
                        blocks += ArticleBlock.Attachment(name, href)
                    } else {
                        val text = el.text()
                        // 只把真正的 http(s) 外链做成可点击 Link；
                        // javascript:/相对锚点的 anchor 直接降级为文本，避免点击落空。
                        if ((href.startsWith("http://", ignoreCase = true) ||
                                href.startsWith("https://", ignoreCase = true)) &&
                            text.isNotBlank()
                        ) {
                            pendingRuns += InlineRun.Link(text.trim(), href, style)
                        } else {
                            walkChildren(el, style)
                        }
                    }
                }

                "p", "div", "section", "article", "blockquote" -> {
                    val merged = mergeStyle(style, el)
                    val hasBlockChildren = el.children().any { it.tagName().lowercase() in BLOCK_TAGS }
                    if (hasBlockChildren) {
                        flushParagraph()
                        walkChildren(el, merged)
                        flushParagraph()
                    } else {
                        // 纯内联容器：把内容收进 pendingRuns 后立刻 flush，
                        // 维持「div 之间天然换段」语义。
                        walkChildren(el, merged)
                        flushParagraph()
                    }
                }

                "ul", "ol" -> {
                    flushParagraph()
                    walkChildren(el, style)
                    flushParagraph()
                }
                "li" -> {
                    flushParagraph()
                    pendingRuns += InlineRun.Text("• ", style)
                    walkChildren(el, style)
                    flushParagraph()
                }

                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    // 标题在通知正文里很罕见；当作加粗段落即可，省一类型
                    flushParagraph()
                    walkChildren(el, style.copy(bold = true))
                    flushParagraph()
                }

                "b", "strong" -> walkChildren(el, style.copy(bold = true))
                "i", "em" -> walkChildren(el, style.copy(italic = true))
                "u" -> walkChildren(el, style.copy(underline = true))

                "font" -> {
                    val color = parseHtmlColor(el.attr("color"))
                    walkChildren(el, if (color != null) style.copy(color = color) else style)
                }
                "span" -> walkChildren(el, mergeStyle(style, el))

                else -> walkChildren(el, style)
            }
        }

        private fun parseTable(table: Element): List<List<List<InlineRun>>> {
            val rows = mutableListOf<List<List<InlineRun>>>()
            for (tr in table.select("tr")) {
                val row = mutableListOf<List<InlineRun>>()
                for (cell in tr.children()) {
                    val tag = cell.tagName().lowercase()
                    if (tag != "td" && tag != "th") continue
                    // inlineOnly = true:单元格内的 <p>/<div> 不会切段,文本留在 pendingRuns 里
                    row += BodyWalker(inlineOnly = true).collectCellInline(cell)
                }
                if (row.isNotEmpty()) rows += row
            }
            return rows
        }

        private fun collectCellInline(cell: Element): List<InlineRun> {
            walkChildren(cell, InlineStyle())
            // 单元格内允许 `<br>` 换行但不需要分段，所以不调 flushParagraph
            val collected = normalise(pendingRuns.toList())
            pendingRuns.clear()
            return collected
        }

        private fun flushParagraph() {
            if (inlineOnly) {
                // 单元格模式：原本会切段的位置改成在 pendingRuns 里塞一个 LineBreak，
                // 文本不离开 pendingRuns。collectCellInline 末尾交给 normalise 清理首尾 LB。
                if (pendingRuns.isNotEmpty() && pendingRuns.lastOrNull() !is InlineRun.LineBreak) {
                    pendingRuns += InlineRun.LineBreak
                }
                return
            }
            if (pendingRuns.isEmpty()) return
            val normalised = normalise(pendingRuns.toList())
            pendingRuns.clear()
            if (normalised.isNotEmpty()) blocks += ArticleBlock.Paragraph(normalised)
        }

        /**
         * 段内规范化：
         *  - 合并相邻、样式相同的 Text；
         *  - 多空白塌缩成单个空格（HTML 渲染语义）；
         *  - 去掉首尾纯空白 Text **以及首尾的 LineBreak**（单元格模式下 flushParagraph 会留尾 LB）；
         *  - 全为空白则返回空列表（这样上游 flush 不会塞空段）。
         */
        private fun normalise(runs: List<InlineRun>): List<InlineRun> {
            if (runs.isEmpty()) return emptyList()
            val collapsed = runs.map { run ->
                when (run) {
                    is InlineRun.Text -> run.copy(text = run.text.replace(WHITESPACE, " "))
                    else -> run
                }
            }
            val merged = mutableListOf<InlineRun>()
            for (run in collapsed) {
                val last = merged.lastOrNull()
                if (run is InlineRun.Text && last is InlineRun.Text && last.style == run.style) {
                    merged[merged.lastIndex] = last.copy(text = last.text + run.text)
                } else {
                    merged += run
                }
            }
            // 修剪首尾空白文本 / LineBreak
            while (merged.isNotEmpty()) {
                val first = merged.first()
                if (first is InlineRun.LineBreak) { merged.removeAt(0); continue }
                if (first is InlineRun.Text && first.text.isBlank()) { merged.removeAt(0); continue }
                break
            }
            while (merged.isNotEmpty()) {
                val last = merged.last()
                if (last is InlineRun.LineBreak) { merged.removeAt(merged.lastIndex); continue }
                if (last is InlineRun.Text && last.text.isBlank()) { merged.removeAt(merged.lastIndex); continue }
                break
            }
            if (merged.all { it is InlineRun.Text && it.text.isBlank() }) return emptyList()
            return merged
        }

        private fun mergeStyle(base: InlineStyle, el: Element): InlineStyle {
            val styleAttr = el.attr("style")
            if (styleAttr.isBlank()) return base
            val color = STYLE_COLOR.find(styleAttr)?.groupValues?.get(1)?.let { parseHtmlColor(it) }
            return if (color != null) base.copy(color = color) else base
        }

        private fun isAttachment(href: String): Boolean {
            if (href.isBlank()) return false
            val lower = href.lowercase()
            if ("download.aspx" in lower) return true
            val pathOnly = lower.substringBefore('?').substringBefore('#')
            val ext = pathOnly.substringAfterLast('.', "")
            return ext.isNotBlank() && ext in ATTACHMENT_EXTENSIONS
        }

        private fun hrefFileName(href: String): String {
            val path = href.substringBefore('?').substringBefore('#')
            val tail = path.substringAfterLast('/', missingDelimiterValue = path)
            return tail.ifBlank { href }
        }

        private fun parseHtmlColor(value: String): Int? {
            val v = value.trim().lowercase()
            if (v.isEmpty()) return null
            NAMED_COLORS[v]?.let { return it }
            val hex = HEX_COLOR.find(v)?.groupValues?.get(1) ?: return null
            return runCatching { (0xFF000000.toInt()) or hex.toLong(16).toInt() }.getOrNull()
        }

        companion object {
            private val WHITESPACE = Regex("""\s+""")
        }
    }
}
