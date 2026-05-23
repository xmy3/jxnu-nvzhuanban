package cn.jxnu.nvzhuanban.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileData(
    val user: UserProfile,
)

class ProfileViewModel(
    private val authRepo: AuthRepository = AuthRepository.instance,
    private val gradeRepo: GradeRepository = GradeRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProfileData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileData>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val base = (authRepo.state.value as? AuthState.LoggedIn)?.profile
                if (base == null) {
                    _state.value = UiState.Error("未登录")
                    return@launch
                }
                _state.value = UiState.Success(ProfileData(base))
                if (base.needsGradeEnrichment()) enrichFromGrades(base)
            } catch (t: Throwable) {
                _state.value = UiState.Error(t.toUserMessage())
            }
        }
    }

    private fun enrichFromGrades(base: UserProfile) {
        viewModelScope.launch {
            val meta: GradePage.StudentMeta = runCatching { gradeRepo.fetchAll().meta }
                .getOrNull()
                ?: return@launch
            updateProfile { current ->
                current.copy(
                    college = meta.college?.takeIf { it.isNotBlank() } ?: current.college,
                    major = meta.major?.takeIf { it.isNotBlank() } ?: current.major,
                    className = meta.className?.takeIf { it.isNotBlank() } ?: current.className,
                    name = base.name.ifBlank { meta.name.orEmpty().ifBlank { current.name } },
                    studentId = base.studentId.ifBlank { meta.studentId.orEmpty().ifBlank { current.studentId } },
                    cumulativeCredits = meta.totalCredit ?: current.cumulativeCredits,
                )
            }
        }
    }

    private fun updateProfile(transform: (UserProfile) -> UserProfile) {
        val current = (_state.value as? UiState.Success)?.data?.user ?: return
        _state.value = UiState.Success(ProfileData(transform(current)))
    }

    fun logout() {
        // logout 现在是 suspend：需要按顺序清掉 6 个 Repository 缓存 + 课程覆盖 + 通知锚点 + widget snapshot
        viewModelScope.launch {
            authRepo.logout()
        }
    }

    private fun UserProfile.needsGradeEnrichment(): Boolean =
        college.isBlank() || major.isBlank() || className.isBlank() || cumulativeCredits <= 0f
}
