package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.SemesterSummary
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.GraduationAuditRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GradesData(
    val semesters: List<SemesterSummary>,
    val graduationMinimumCredits: Float? = null,
)

class GradesViewModel(
    private val repo: GradeRepository = GradeRepository.instance,
    private val graduationAuditRepo: GraduationAuditRepository = GraduationAuditRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<GradesData>>(UiState.Loading)
    val state: StateFlow<UiState<GradesData>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetchData(refresh = false))
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
                _state.value = UiState.Success(fetchData(refresh = true))
            } catch (_: Throwable) {
                // Keep the previous state visible; retry from the error state is handled by load().
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchData(refresh: Boolean): GradesData {
        val semesters = if (refresh) repo.refresh().semesters else repo.getAllSemesters()
        val minimumCredits = runCatching {
            if (refresh) graduationAuditRepo.refresh().minimumCredits else graduationAuditRepo.fetch().minimumCredits
        }.getOrNull()
        return GradesData(
            semesters = semesters,
            graduationMinimumCredits = minimumCredits,
        )
    }
}
