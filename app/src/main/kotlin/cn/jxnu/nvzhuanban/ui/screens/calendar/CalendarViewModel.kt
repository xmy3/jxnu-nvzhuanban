package cn.jxnu.nvzhuanban.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.CalendarEntry
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.CalendarRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 校历列表 ViewModel。
 *
 * 数据是 jwc 的纯静态索引页，更新很慢。模式照搬 ExamsViewModel 的精简版（单 Repository）。
 * 下拉刷新失败时静默保留旧列表，避免一次抖动把整页清空。
 */
class CalendarViewModel(
    private val repo: CalendarRepository = CalendarRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<CalendarEntry>>>(UiState.Loading)
    val state: StateFlow<UiState<List<CalendarEntry>>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetchAll())
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
                _state.value = UiState.Success(repo.refresh())
            } catch (_: Throwable) {
                // 静默保留旧数据；如果是首次加载就失败，state 仍是 Loading/Error，由 load() 走错误态
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
