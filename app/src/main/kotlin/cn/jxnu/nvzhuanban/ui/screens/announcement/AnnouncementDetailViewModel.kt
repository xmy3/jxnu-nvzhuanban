package cn.jxnu.nvzhuanban.ui.screens.announcement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.ArticleDetail
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.ArticleDetailRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnnouncementDetailViewModel(
    val articleId: String,
    private val repo: ArticleDetailRepository = ArticleDetailRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ArticleDetail>>(UiState.Loading)
    val state: StateFlow<UiState<ArticleDetail>> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (!articleId.matches(VALID_ID)) {
            _state.value = UiState.Error("文章编号无效")
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(repo.fetch(articleId))
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    companion object {
        private val VALID_ID = Regex("""\d+""")

        fun factory(articleId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnnouncementDetailViewModel(articleId) as T
            }
    }
}
