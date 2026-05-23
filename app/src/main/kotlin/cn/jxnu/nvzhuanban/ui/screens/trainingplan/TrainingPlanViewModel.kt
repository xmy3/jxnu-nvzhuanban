package cn.jxnu.nvzhuanban.ui.screens.trainingplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.TrainingPlan
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrainingPlanViewModel(
    private val repo: GraduationAuditRepository = GraduationAuditRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TrainingPlan>>(UiState.Loading)
    val state: StateFlow<UiState<TrainingPlan>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetch().trainingPlan)
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
                _state.value = UiState.Success(repo.refresh().trainingPlan)
            } catch (_: Throwable) {
                // Keep the previous state visible; retry from the error state is handled by load().
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
