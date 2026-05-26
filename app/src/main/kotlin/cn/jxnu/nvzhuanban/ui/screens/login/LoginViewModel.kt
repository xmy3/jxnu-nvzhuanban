package cn.jxnu.nvzhuanban.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val rememberMe: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class LoginViewModel(
    private val authRepo: AuthRepository = AuthRepository.instance,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            username = authRepo.rememberedUsername().orEmpty(),
            rememberMe = authRepo.isRememberMeOn(),
        )
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 暴露全局认证态：Application 启动期间如果 cookie 恢复成功，登录页会跳过自身。 */
    val authState: StateFlow<AuthState> = authRepo.state

    init {
        val authError = authRepo.state.value as? AuthState.Error
        if (authError != null) {
            _state.update { it.copy(error = authError.message) }
        }
    }

    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onCaptchaChange(v: String) = _state.update { it.copy(captcha = v, error = null) }
    fun onRememberMeChange(v: Boolean) {
        val actual = authRepo.updateRememberMe(v)
        _state.update {
            it.copy(
                rememberMe = actual,
                error = if (v && !actual) "当前设备安全存储不可用，已关闭自动登录" else null,
            )
        }
    }

    fun submit() {
        val cur = _state.value
        if (cur.isLoading) return
        if (cur.username.isBlank() || cur.password.isBlank()) {
            _state.update { it.copy(error = "请输入学号和密码") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepo.login(cur.username, cur.password, cur.captcha)
                _state.update { it.copy(isLoading = false, success = true, password = "") }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.toUserMessage("登录失败，请稍后再试"),
                    )
                }
            }
        }
    }

    /**
     * 一次性消费"登录成功但有衍生异常"的告警（目前只有"keystore 写失败 → rememberMe 被关"
     * 一种）。LoginScreen 在 success 触发导航前调用，用 Toast 显示——snackbar 在导航瞬间
     * 会被吃掉。
     */
    fun consumeLoginWarning(): String? = authRepo.consumeLastLoginWarning()
}
