package cn.jxnu.nvzhuanban.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * load/refresh + [UiState] 的通用模板，子类只实现 [fetch]。
 *
 *  - [load]：整页置 Loading 后拉数据，失败置 Error（[ErrorState] 的 onRetry 也走这里）
 *  - [refresh]：带防重入；**失败静默保留旧数据**，只复位转圈——这是全 app 下拉刷新的
 *    统一约定，子类不要绕开 refresh 自己写一份
 *
 * 基类不在 init 里自动 load（那时子类的 ctor 属性还没初始化）：需要进页即加载的子类
 * 自己写 `init { load() }`，懒加载的（如 TestGradesViewModel）由 UI 显式触发。
 */
abstract class UiStateViewModel<T> : ViewModel() {

    protected val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 拉取整页数据；refresh=true 时应 bypass Repository 缓存。 */
    protected abstract suspend fun fetch(refresh: Boolean): T

    open fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetch(refresh = false))
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetch(refresh = true))
            } catch (_: Throwable) {
                // Keep the previous state visible; retry from the error state is handled by load().
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
