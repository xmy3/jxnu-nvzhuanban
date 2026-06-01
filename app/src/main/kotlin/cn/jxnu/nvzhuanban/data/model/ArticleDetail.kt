package cn.jxnu.nvzhuanban.data.model

/**
 * 教务通知详情页结构化模型。
 *
 * 数据来源：`Portal/ArticlesView.aspx?id=<id>`，由
 * [cn.jxnu.nvzhuanban.data.network.pages.ArticleDetailPage] 把 jwc 的 PC 模板（含 banner / 导航 / 页脚）
 * 砍掉只剩嵌套 `#main-content` 正文，再按块/内联模型重排，最终在 Compose 里 LazyColumn 渲染。
 */
data class ArticleDetail(
    val title: String,
    /**
     * 发布时间原文，已剥掉 jwc 模板里的「【时间：…】」外壳，例如「2026-05-22 16:28:25」。
     * 给 UI 当一行显示用，不再二次 parse —— 教务网偶尔会输出非标准时间，强解析会丢内容。
     */
    val postedAt: String,
    val blocks: List<ArticleBlock>,
    /**
     * 该详情其实是 jwc 在**无有效会话**时返回的「对不起，该文档需要登录后再查看!」占位页
     * （HTTP 200、同域、不重定向，所以 [cn.jxnu.nvzhuanban.data.network.JwcResponseGuard]
     * 不会把它判成会话过期）。由 [cn.jxnu.nvzhuanban.data.network.pages.ArticleDetailPage] 按标题特征识别。
     *
     * 为 true 时 [blocks] 被清空，UI 不渲染正文，而是显示「去登录」引导并导向 **app 内登录页**
     * （历史上这里会把占位页里的「登录」超链接渲染成外链 → 跳浏览器官方教务处，体验割裂）。
     */
    val requiresLogin: Boolean = false,
)

/**
 * 正文块节点。Paragraph / Image / Table / Attachment / Divider 五类，覆盖 jwc 通知 99% 的格式。
 * 渲染顺序按解析时的文档流。
 */
sealed interface ArticleBlock {
    data class Paragraph(val runs: List<InlineRun>) : ArticleBlock

    /**
     * [src] 在 Jsoup 解析阶段已被 baseUri 解析为绝对 URL，渲染走
     * [cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage]（自动带 session cookie，
     * 兼容站内 /MyControl/ 路径的鉴权图片）。
     */
    data class Image(val src: String, val alt: String?) : ArticleBlock

    /** rows[行][列] = 单元格内的内联富文本。jwc 通知偶尔嵌入小型课表/时间表。 */
    data class Table(val rows: List<List<List<InlineRun>>>) : ArticleBlock

    /**
     * 附件链接（PDF / DOC / XLS / ZIP 等）。UI 上以带图标卡片展示，点击调 ACTION_VIEW；
     * 同一 anchor 不会同时进 [Attachment] 和 [Paragraph]，由 parser 二选一。
     */
    data class Attachment(val name: String, val url: String) : ArticleBlock

    /** `<hr>`。 */
    data object Divider : ArticleBlock
}

sealed interface InlineRun {
    data class Text(val text: String, val style: InlineStyle = InlineStyle()) : InlineRun

    /**
     * 段落内的超链接。只在 url 通过 http(s) 协议判定通过时才生成，
     * 站内 JS / 相对锚点会被 parser 降级成普通 [Text]，避免渲染层再做兜底。
     */
    data class Link(val text: String, val url: String, val style: InlineStyle = InlineStyle()) : InlineRun

    /** `<br>`。Paragraph 内部换行用，独立成块的 `<br>` 在 parser 里被忽略。 */
    data object LineBreak : InlineRun
}

/**
 * 内联样式。当前只支持 jwc 通知里实际会用到的几种：粗 / 斜 / 下划线 / 颜色。
 * 字号、字体等差异不抽出来，统一由主题 typography 决定。
 */
data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** ARGB int from `style="color:#RRGGBB"` 或 `<font color="...">`。null = 跟随主题 onSurface。 */
    val color: Int? = null,
)
