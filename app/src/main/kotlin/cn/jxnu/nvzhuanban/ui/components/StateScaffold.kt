package cn.jxnu.nvzhuanban.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 通用 UI 状态。每个 ViewModel 暴露 `StateFlow<UiState<T>>`。
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

/**
 * 根据 [state] 自动渲染对应的占位/数据。
 *
 *  - Loading → [LoadingState]
 *  - Error   → [ErrorState] + onRetry
 *  - Success → 用 [Box] 套上 [modifier] 后调 [content] 渲染真正的内容
 *
 * 三个分支都会消费 [modifier]，调用方就只用挂一次 padding 即可，无需在 Success 内部再 padding 一遍。
 */
@Composable
fun <T> StateScaffold(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Loading -> LoadingState(modifier = modifier)
        is UiState.Error -> ErrorState(message = state.message, modifier = modifier, onRetry = onRetry)
        is UiState.Success -> Box(modifier = modifier) { content(state.data) }
    }
}
