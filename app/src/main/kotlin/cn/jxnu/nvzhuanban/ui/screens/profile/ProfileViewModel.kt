package cn.jxnu.nvzhuanban.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.network.pages.GradePage
import cn.jxnu.nvzhuanban.data.network.toUpdateMessage
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.repository.GradeRepository
import cn.jxnu.nvzhuanban.data.repository.UpdateRepository
import cn.jxnu.nvzhuanban.data.storage.UpdatePrefs
import cn.jxnu.nvzhuanban.ui.components.UiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileData(
    val user: UserProfile,
)

/**
 * 用户主动「检查更新」点击的一次性事件，UI 用 `LaunchedEffect` 收并 Snackbar 呈现。
 * 区分 [Newer] 和 [Latest] 让 UI 决定是弹 Dialog 还是 Snackbar 报"已是最新版本"。
 */
sealed interface UpdateCheckResult {
    data object Checking : UpdateCheckResult
    data object Latest : UpdateCheckResult
    data class Newer(val release: AppRelease) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    // 不放进主构造器：Kotlin 默认参数不会生成 `(Application)` 单参重载，
    // SavedStateViewModelFactory 通过反射找 AndroidViewModel 标准构造器找不到就闪退。
    // 这个坑 ScheduleViewModel 已经踩过一次，参见那边的相同注释。
    private val authRepo: AuthRepository = AuthRepository.instance
    private val gradeRepo: GradeRepository = GradeRepository.instance
    private val updateRepo: UpdateRepository = UpdateRepository.instance

    private val _state = MutableStateFlow<UiState<ProfileData>>(UiState.Loading)
    val state: StateFlow<UiState<ProfileData>> = _state.asStateFlow()

    /**
     * 直接转发 [UpdateRepository.latestRelease] —— App 启动时 [NvzhuanbanApp] 已经跑过
     * 一次 [UpdateRepository.checkIfDue]，VM 不在 init 里重复触发。
     */
    val latestRelease: StateFlow<AppRelease?> = updateRepo.latestRelease

    private val _checkResult = MutableSharedFlow<UpdateCheckResult>(extraBufferCapacity = 1)
    val checkResult: SharedFlow<UpdateCheckResult> = _checkResult.asSharedFlow()

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

    /**
     * 用户主动点「检查更新」时调用。绕过 24h 限频，结果通过 [checkResult] 一次性事件
     * 推回 UI。失败有 message，UI 显示 Snackbar；拿到更新版本则发 [UpdateCheckResult.Newer]
     * 让 UI 弹 Dialog；已是最新发 [UpdateCheckResult.Latest] 让 UI Snackbar 提示。
     */
    fun forceCheck() {
        viewModelScope.launch {
            _checkResult.emit(UpdateCheckResult.Checking)
            val versionName = currentVersionName()
            runCatching { updateRepo.forceCheck(versionName) }.fold(
                onSuccess = { release ->
                    _checkResult.emit(
                        if (release != null) UpdateCheckResult.Newer(release)
                        else UpdateCheckResult.Latest,
                    )
                },
                onFailure = { t ->
                    _checkResult.emit(UpdateCheckResult.Failed(t.toUpdateMessage()))
                },
            )
        }
    }

    /**
     * 标记某个版本"不再提醒"。仅影响静默检查路径的判断（再点 SettingsRow 仍能 forceCheck
     * 重新看到 Dialog，相当于自带"取消跳过"入口）。
     */
    fun skipVersion(tag: String) {
        UpdatePrefs.setSkippedVersion(tag)
    }

    private fun currentVersionName(): String = runCatching {
        val ctx = getApplication<Application>()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"

    private fun UserProfile.needsGradeEnrichment(): Boolean =
        college.isBlank() || major.isBlank() || className.isBlank() || cumulativeCredits <= 0f
}
