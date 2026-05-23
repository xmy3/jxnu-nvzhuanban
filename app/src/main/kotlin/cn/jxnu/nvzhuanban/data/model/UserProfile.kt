package cn.jxnu.nvzhuanban.data.model

data class UserProfile(
    val studentId: String,
    val name: String,
    val college: String,
    val major: String,
    val className: String,
    val grade: Int,
    val avatarUrl: String? = null,
    val cumulativeCredits: Float = 0f,
    val graduationMinimumCredits: Float? = null,
)

/** 登录状态：未登录 / 登录中 / 已登录 / 错误 */
sealed interface AuthState {
    data object LoggedOut : AuthState
    data object Loading : AuthState
    data class LoggedIn(val profile: UserProfile) : AuthState
    data class Error(val message: String) : AuthState
}
