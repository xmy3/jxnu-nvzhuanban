package cn.jxnu.nvzhuanban.ui.screens.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.MakeupExam
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.ExamRepository
import cn.jxnu.nvzhuanban.data.repository.MakeupExamRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 考试 Tab 同时承载「常规考试」和「补缓考」两份数据。
 *
 * 两个接口互相独立，并行拉以省一个 RTT；任意一边失败都把整个页面置为 Error，避免半填半空、
 * 用户以为没有补缓考实际是网络炸了的歧义。
 */
data class ExamsBundle(
    val regular: List<Exam>,
    val makeup: List<MakeupExam>,
)

class ExamsViewModel(
    private val repo: ExamRepository = ExamRepository.instance,
    private val makeupRepo: MakeupExamRepository = MakeupExamRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ExamsBundle>>(UiState.Loading)
    val state: StateFlow<UiState<ExamsBundle>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetchBoth(refresh = false))
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    /** 下拉刷新：保留旧数据，仅顶部转圈；失败时静默保留旧数据。 */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(fetchBoth(refresh = true))
            } catch (_: Throwable) {
                // 保留 _state；错误态走 load() 路径
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchBoth(refresh: Boolean): ExamsBundle = coroutineScope {
        val exams = async {
            if (refresh) repo.refresh() else repo.getUpcomingExams()
        }
        val makeups = async {
            if (refresh) makeupRepo.refresh() else makeupRepo.fetchAll()
        }
        val (e, m) = awaitAll(exams, makeups)
        @Suppress("UNCHECKED_CAST")
        ExamsBundle(
            regular = (e as List<Exam>).sortedBy { it.startTime },
            makeup = m as List<MakeupExam>,
        )
    }
}
