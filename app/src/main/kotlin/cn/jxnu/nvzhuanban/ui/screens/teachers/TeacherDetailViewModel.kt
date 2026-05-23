package cn.jxnu.nvzhuanban.ui.screens.teachers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.TeacherInfo
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.TeacherDetailRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TeacherDetailViewModel(
    val userNum: String,
    val displayName: String,
    private val repo: TeacherDetailRepository = TeacherDetailRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TeacherInfo>>(UiState.Loading)
    val state: StateFlow<UiState<TeacherInfo>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetch(userNum))
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    companion object {
        fun factory(userNum: String, name: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TeacherDetailViewModel(userNum, name) as T
            }
    }
}
