package cn.jxnu.nvzhuanban.data.model

import java.time.LocalDate

/**
 * 教务通知 / 通告 / 图文新闻的列表条目。
 *
 * 数据来源：
 *   - [AnnouncementType.NOTIFICATION] / [AnnouncementType.BULLETIN]：
 *     `jwc.jxnu.edu.cn/Portal/ArticlesList.aspx?type={Jwtz|Jwgg}`
 *   - [AnnouncementType.PICTURE_NEWS]：
 *     `jwc.jxnu.edu.cn/Portal/ArticlesPictureNews.aspx?page=N`
 *
 * 详情页 [detailUrl] 是 jwc 自家的 WebView 兼容页，App 内 WebView 直接打开。
 * ASP.NET 路由对 query 参数大小写不敏感，所以图文新闻列表里的 `ID=` 也能用 `id=` 复用同一详情页。
 */
data class Announcement(
    /** 教务系统内部文章 id（ArticlesView.aspx?id=XXXX 末尾的数字）。 */
    val id: String,
    val title: String,
    val date: LocalDate,
    val type: AnnouncementType,
    /** 图文新闻列表带的 240×180 缩略图绝对 URL；通知 / 通告类型为 null。 */
    val thumbnailUrl: String? = null,
) {
    val detailUrl: String
        get() = "https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=$id"

    /** 跨 type 唯一键，用于「上次见到的最新一条」锚点等需要稳定 id 的场景。 */
    val uniqueKey: String
        get() = "${type.name}:$id"
}

/**
 * 通知类型。
 *
 * @property displayName UI 上展示给用户的标签
 */
enum class AnnouncementType(val displayName: String) {
    /** 教务通知（ArticlesList.aspx?type=Jwtz）。 */
    NOTIFICATION("教务通知"),

    /** 教务通告（ArticlesList.aspx?type=Jwgg）。 */
    BULLETIN("教务通告"),

    /** 图文新闻（ArticlesPictureNews.aspx），带缩略图。 */
    PICTURE_NEWS("图文新闻"),
}
