package cn.jxnu.nvzhuanban.ui.screens.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.TestGradeReport
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.TestGradeRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「考试出分」子 Tab 的 ViewModel。与 [GradesViewModel] 分离，便于：
 *  - 两个 Tab 独立刷新（互不影响 isRefreshing 转圈）
 *  - 各自维护自己的缓存与错误态
 *
 * 进入 Grades 页时不立即触发（init 不 load），首次切到该 Tab 时由 UI 显式调用 [load]。
 * 这样避免学期成绩用户不去看出分页时也付出一次额外网络请求。
 */
class TestGradesViewModel(
    private val repo: TestGradeRepository = TestGradeRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TestGradeReport>>(UiState.Loading)
    val state: StateFlow<UiState<TestGradeReport>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var loaded = false

    /** 首次进入该 Tab 时由 UI 调用；已加载过的会自动跳过（除非走 [refresh]）。 */
    fun loadIfNeeded() {
        if (loaded) return
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetchAll())
                loaded = true
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
                loaded = true
            } catch (_: Throwable) {
                // 保留旧数据
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
