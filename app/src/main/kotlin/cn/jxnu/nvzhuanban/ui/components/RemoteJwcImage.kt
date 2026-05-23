package cn.jxnu.nvzhuanban.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import cn.jxnu.nvzhuanban.data.network.JwcClient

/**
 * 用应用全局 [cn.jxnu.nvzhuanban.data.network.JxnuHttpClient] 拉一张教务网图片解码成 [ImageBitmap]。
 *
 * 为什么不用 Coil：
 * 1. 教务图片接口在 /MyControl/ 路径下，需要 OkHttp 持久化 session cookie 才能拿到；
 *    用 Coil 默认 ImageLoader 会自己开一份 OkHttp，没登录态。
 * 2. jwc 的 HTTPS 对 Android WebView TLS 栈不友好（见 memory: project-webview-tls-quirk），
 *    OkHttp 同样的 URL 没事 —— 复用同一份 client 避免再写 TrustManager 配置。
 * 3. 全应用就一个头像位 + 通知正文偶尔的内嵌图要远程图片，不值得加依赖。
 *
 * **加载后的布局策略由调用方控制**：
 * - 头像 / 缩略图：传固定尺寸 [modifier]（如 `Modifier.size(80.dp)`）+ 默认 [ContentScale.Crop]，
 *   图片裁切居中铺满。
 * - 通知正文长图：传 `Modifier.fillMaxWidth().heightIn(min = …)` + [ContentScale.FillWidth]，
 *   宽度铺满 + 高度按图片真实宽高比自适应（避免长图被截断）。`heightIn(min = …)` 是给 loading
 *   期间的 fallback 用的——加载成功后 Image 按 intrinsic ratio 自己撑开。
 *
 * 加载中 / 失败时显示 [fallback]。[url] 变化或被置为 null 会清空当前图。
 */
@Composable
fun RemoteJwcImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val bmp = runCatching {
            // getBytesAuth：session 失效时自动 reauth + 重放一次，避免头像加载失败把用户踢出
            val bytes = JwcClient.getBytesAuth(url, "图片返回空响应")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("decode 失败")
        }.getOrNull()
        bitmap = bmp?.asImageBitmap()
    }
    val bmp = bitmap
    if (bmp != null) {
        // 直接把 modifier 作用在 Image 上：固定尺寸调用方拿到固定框，
        // fillMaxWidth 调用方拿到「宽度铺满 + 高度按 intrinsic ratio」自适应行为。
        Image(
            bitmap = bmp,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        // loading / 失败：用 Box wrap fallback 居中。modifier 决定占位框大小
        // （调用方应提供 min 高度，否则 fillMaxWidth 时 Box 会塌缩为 0 高）。
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            fallback()
        }
    }
}

