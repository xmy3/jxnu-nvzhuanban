package cn.jxnu.nvzhuanban.ui.screens.userschedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.UserScheduleRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserScheduleViewModel(
    val scheduleUrl: String,
    val displayName: String,
    private val repo: UserScheduleRepository = UserScheduleRepository(scheduleUrl),
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SchedulePage.Parsed>>(UiState.Loading)
    val state: StateFlow<UiState<SchedulePage.Parsed>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetch())
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    /** 用户点学期 chip：本地缓存命中则秒切，没命中走一次 POST。 */
    fun selectSemester(value: String) {
        viewModelScope.launch {
            try {
                val parsed = repo.selectSemester(value)
                _state.value = UiState.Success(parsed)
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    companion object {
        fun factory(scheduleUrl: String, displayName: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UserScheduleViewModel(scheduleUrl, displayName) as T
            }
    }
}
