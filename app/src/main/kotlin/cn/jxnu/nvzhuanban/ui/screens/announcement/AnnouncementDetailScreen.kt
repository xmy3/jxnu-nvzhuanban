package cn.jxnu.nvzhuanban.ui.screens.announcement

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.ArticleBlock
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.model.InlineRun
import cn.jxnu.nvzhuanban.data.model.InlineStyle
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.FullScreenImageViewer
import cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import java.net.URI
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailScreen(
    articleId: String,
    onBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val vm: AnnouncementDetailViewModel = viewModel(
        key = "announcement_detail_$articleId",
        factory = AnnouncementDetailViewModel.factory(articleId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 「在浏览器中打开」始终可点：哪怕详情解析失败，也允许用户用浏览器查看原文
    val externalUrl = remember(articleId) {
        if (articleId.matches(Regex("""\d+"""))) {
            "https://jwc.jxnu.edu.cn/Portal/ArticlesView.aspx?id=$articleId"
        } else null
    }
    // 正文里的内嵌图被点击后进入全屏查看器；null = 不显示
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.announcement_detail_title)) },
                navigationIcon = { BackNavigationIcon(onBack) },
                actions = {
                    if (externalUrl != null) {
                        IconButton(onClick = { openExternalHttpUrl(context, externalUrl) }) {
                            Icon(
                                Icons.Outlined.OpenInBrowser,
                                contentDescription = stringResource(R.string.announcement_open_external),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onRetry = vm::load,
            loading = { m -> cn.jxnu.nvzhuanban.ui.components.ArticleSkeleton(modifier = m) },
        ) { detail ->
            if (detail.requiresLogin) {
                // jwc 无会话时返回的"需要登录"占位页：不渲染正文，给出 app 内登录引导。
                LoginRequiredView(onLogin = onNavigateToLogin)
            } else {
                ArticleBody(
                    detail = detail,
                    onImageClick = { url -> imageViewerUrl = url },
                )
            }
        }
    }

    // 渲染在 Scaffold 外面：Dialog 会自己起一个 window，覆盖整个 activity
    imageViewerUrl?.let { url ->
        FullScreenImageViewer(
            url = url,
            contentDescription = null,
            onDismiss = { imageViewerUrl = null },
        )
    }
}

/**
 * 「需要登录」占位状态：替代正文，居中展示一枚图标 + 文案 + 「去登录」按钮。
 * 按钮回调 [onLogin] 由 AppNav 接到 app 内登录页（不再是历史上那条跳浏览器官方教务处的外链）。
 */
@Composable
private fun LoginRequiredView(onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.announcement_login_required_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.announcement_login_required_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onLogin,
            modifier = Modifier
                .widthIn(min = 160.dp)
                .heightIn(min = 50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.announcement_login_required_action),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ArticleBody(
    detail: ArticleDetail,
    onImageClick: (String) -> Unit,
) {
    // SelectionContainer 让正文（标题 / 时间 / 段落 / 表格文字）支持长按选中 + 复制。
    // Compose 1.7 起，选择手势与 InlineRun.Link 的点击、图片/附件的 clickable 可共存。
    // 已知限制：LazyColumn 会回收滚出屏幕的 item，跨越回收边界的选择会断；可见区域内选/复制正常。
    SelectionContainer(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                if (detail.title.isNotBlank()) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (detail.postedAt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail.postedAt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            if (detail.blocks.isEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正文为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // contentType 让 LazyColumn 只在同类 block（段落/图片/表格…）之间复用节点，
            // 避免滚出屏的 Table 被拿去承载 Paragraph 这类近似整棵重建的错配
            items(detail.blocks, contentType = { it::class }) { block ->
                Spacer(modifier = Modifier.height(8.dp))
                BlockView(block, onImageClick = onImageClick)
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun BlockView(
    block: ArticleBlock,
    onImageClick: (String) -> Unit,
) {
    when (block) {
        is ArticleBlock.Paragraph -> ParagraphView(block.runs)
        is ArticleBlock.Image -> ImageBlockView(block, onImageClick = onImageClick)
        is ArticleBlock.Table -> TableBlockView(block.rows)
        is ArticleBlock.Attachment -> AttachmentView(block)
        ArticleBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ParagraphView(runs: List<InlineRun>) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    // 正文段落直接坐在 Scaffold 的 background 上（详情页未覆盖 containerColor）。
    val background = MaterialTheme.colorScheme.background
    val annotated = remember(runs, linkColor, baseColor, background) {
        buildParagraph(runs, linkColor, baseColor, background) { url ->
            openExternalHttpUrl(context, url)
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = baseColor,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageBlockView(
    image: ArticleBlock.Image,
    onImageClick: (String) -> Unit,
) {
    // Surface 只约束宽度铺满，高度交给内部 RemoteJwcImage 按图片真实宽高比自适应。
    // <img> 带像素 width/height（Word 粘贴产物通常带）时用 aspectRatio 给 loading 占位精确预留高度，
    // 属性可信时加载完成高度零跳变，阅读位置不会被撑开的图片推移；无宽高信息时退回
    // heightIn(min = 160.dp)。预占位只作用于占位分支：加载成功后 ContentScale.FillWidth 让
    // Image 用 intrinsic ratio 自己撑开高度，属性与真实位图不符（手改 HTML / CMS 二次缩放）
    // 也不会把图裁掉一截（jwc 通知里偶尔出现的长图截屏可以完整展示）。
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "查看大图") { onImageClick(image.src) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        val ratio = image.aspectRatio
        RemoteJwcImage(
            url = image.src,
            // jwc 的 img 几乎从不写 alt；给个中文兜底，TalkBack 不至于聚焦到无名称的可点目标
            contentDescription = image.alt ?: "通知配图",
            modifier = Modifier.fillMaxWidth(),
            placeholderModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ratio != null) Modifier.aspectRatio(ratio)
                    else Modifier.heightIn(min = 160.dp),
                ),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            fallback = {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            },
        )
    }
}

@Composable
private fun TableBlockView(rows: List<List<List<InlineRun>>>) {
    if (rows.isEmpty()) return
    val columnCount = rows.maxOf { it.size }
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.horizontalScroll(scroll)) {
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    repeat(columnCount) { col ->
                        if (col > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(40.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                        val cell = row.getOrNull(col).orEmpty()
                        TableCellView(cell, isHeader = rowIndex == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCellView(runs: List<InlineRun>, isHeader: Boolean) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.onSurface
    // 表格单元格行底色是 surface（见 TableBlockView 里的 Row.background）。
    val background = MaterialTheme.colorScheme.surface
    val annotated = remember(runs, linkColor, baseColor, background, isHeader) {
        val styled = if (isHeader) {
            // 表头：所有 Text 强制粗体（保留原 color 等其他属性）
            runs.map { run ->
                when (run) {
                    is InlineRun.Text -> run.copy(style = run.style.copy(bold = true))
                    is InlineRun.Link -> run.copy(style = run.style.copy(bold = true))
                    InlineRun.LineBreak -> run
                }
            }
        } else runs
        buildParagraph(styled, linkColor, baseColor, background) { url ->
            openExternalHttpUrl(context, url)
        }
    }
    Box(
        modifier = Modifier
            .widthIn(min = 80.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = baseColor,
        )
    }
}

@Composable
private fun AttachmentView(attachment: ArticleBlock.Attachment) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openExternalHttpUrl(context, attachment.url) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 把 [InlineRun] 列表搓成可点击的 [AnnotatedString]。
 *
 * Link 使用 [LinkAnnotation.Url] + 自定义 onClick：我们不让系统直接 launch URL，
 * 而是通过 [openExternalHttpUrl] 做协议白名单过滤（只放行 http / https）。
 * 字体的颜色 / 粗细 / 斜体 / 下划线一并 push 进 SpanStyle。
 */
private fun buildParagraph(
    runs: List<InlineRun>,
    linkColor: Color,
    baseColor: Color,
    background: Color,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        when (run) {
            is InlineRun.Text -> withStyle(run.style.toSpanStyle(baseColor, background)) { append(run.text) }
            is InlineRun.LineBreak -> append('\n')
            is InlineRun.Link -> {
                val linkStyles = TextLinkStyles(
                    style = run.style
                        .copy(color = run.style.color ?: linkColor.toArgb())
                        .toSpanStyle(linkColor, background)
                        .copy(textDecoration = TextDecoration.Underline),
                )
                withLink(
                    LinkAnnotation.Url(
                        url = run.url,
                        styles = linkStyles,
                        linkInteractionListener = { link ->
                            if (link is LinkAnnotation.Url) onLinkClick(link.url)
                        },
                    ),
                ) {
                    append(run.text)
                }
            }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

/**
 * jwc 通知正文是 Word 复制粘贴出身，里面的 `style="color:#000"` / `<font color>` 全部按**白底**配色。
 * 暗色主题下直接套用就会「深色字 + 深色底 = 看不见」（见用户反馈：暗黑模式看不到字）。
 *
 * 因此 author 指定的颜色**只在它与当前主题背景 [background] 仍有足够对比度时才保留**，否则回退到
 * 主题 [fallbackColor]（onSurface）——后者天然与背景对比，任何主题下都读得清。效果：
 * - 暗色下纯黑 / 深灰正文 → 回退成浅色 onSurface；
 * - 红色等强调色对比度通常达标 → 原样保留；纯蓝这类在黑底上本就难读的 → 一并回退；
 * - 亮色模式下常见的深色字对比度极高 → 全部保留，零回归（还顺手修了「白底浅色字」反向边缘情况）。
 */
private fun InlineStyle.toSpanStyle(fallbackColor: Color, background: Color): SpanStyle {
    val authored = color?.let { Color(it) }
    val resolvedColor =
        if (authored != null && contrastRatio(authored, background) >= MIN_TEXT_CONTRAST) authored
        else fallbackColor
    return SpanStyle(
        color = resolvedColor,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

/** 低于该 WCAG 对比度（AA 大字号阈值）的内联色判定为「在当前背景上不可读」，回退到主题色。 */
private const val MIN_TEXT_CONTRAST = 3.0f

/** 两色的 WCAG 对比度（1..21）。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.wcagLuminance()
    val lb = b.wcagLuminance()
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** WCAG 相对亮度。Compose [Color] 的 red/green/blue 是 sRGB 伽马编码分量（0..1），正是公式所需输入。 */
private fun Color.wcagLuminance(): Float {
    fun linear(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * linear(red) + 0.7152f * linear(green) + 0.0722f * linear(blue)
}

internal fun openExternalHttpUrl(context: Context, url: String) {
    if (!isExternalHttpUrlAllowed(url)) return
    val uri = runCatching { url.toUri() }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    runCatching { context.startActivity(intent) }
}

internal fun isExternalHttpUrlAllowed(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
}
