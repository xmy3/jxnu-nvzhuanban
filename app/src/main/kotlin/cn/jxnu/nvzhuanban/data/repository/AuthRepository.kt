package cn.jxnu.nvzhuanban.data.repository

import android.content.Context
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.AuthStorage
import cn.jxnu.nvzhuanban.data.network.CasLoginClient
import cn.jxnu.nvzhuanban.data.network.JxnuHttpClient
import cn.jxnu.nvzhuanban.data.network.SecureCredentialStore
import cn.jxnu.nvzhuanban.data.network.pages.UserDefaultPage
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 登录态仓库（Phase 3：免登录）。
 *
 * 状态恢复优先级：
 *  1. 仍有效的 cookie → 直接 LoggedIn（快，~100ms）
 *  2. cookie 失效但有保存的凭证 → 静默重登录 → LoggedIn
 *  3. 都没有 → LoggedOut，跳登录页
 *
 * 凭证存储：用户勾"记住账号"时，密码会被 [SecureCredentialStore] 加密（Android Keystore 硬件密钥）
 * 持久化。仅本机解密，卸载即销毁。
 *
 * [authMutex] 把 [login] / [tryRestoreSession] / [logout] / [expireSession] 串行化：
 * 防止冷启动时用户手敲登录与 tryRestoreSession 并发执行 CAS 登录（两条 clearForHost + login
 * 会把 cookie jar 推到半填半清状态、_state 闪烁 LoggedIn → Error → LoggedIn）。
 */
class AuthRepository private constructor(
    private val appContext: Context,
    private val cas: CasLoginClient,
    private val storage: AuthStorage,
    private val creds: SecureCredentialStore,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val authMutex = Mutex()

    class LoginException(message: String) : RuntimeException(message)

    suspend fun login(username: String, password: String, captcha: String = "") = authMutex.withLock {
        _state.value = AuthState.Loading
        when (val r = cas.login(username.trim(), password, captcha.trim())) {
            is CasLoginClient.Result.Success -> {
                onLoginSuccess(username.trim(), password)
            }
            is CasLoginClient.Result.Failure -> {
                _state.value = AuthState.Error(r.message)
                throw LoginException(r.message)
            }
        }
    }

    private suspend fun onLoginSuccess(username: String, password: String) {
        if (storage.rememberMe) {
            val saved = creds.save(username, password)
            if (saved) {
                storage.lastUsername = username
            } else {
                storage.rememberMe = false
                storage.lastUsername = null
                creds.clear()
            }
        } else {
            creds.clear()
        }
        val profile = runCatching { UserDefaultPage.fetchAndParse(username) }
            .getOrNull()
            ?: UserDefaultPage.parse(username, "")
        _state.value = AuthState.LoggedIn(profile)
    }

    /**
     * 启动时尝试恢复登录态。返回 true 表示已是 LoggedIn，登录页可被跳过。
     *
     * 调用方式（NvzhuanbanApp）：异步发起，UI 在 splash 屏等结果。
     */
    suspend fun tryRestoreSession(): Boolean = authMutex.withLock {
        // 优先走 cookie，快
        if (cas.validateSession()) {
            // 即使本地没保存学号（rememberMe=false 的老用户），也能从 /User/Default.aspx 的
            // lblUserInfor 里把学号 + 姓名解出来 —— 没必要因此把人踢回登录页。
            val username = storage.lastUsername
                ?: creds.load()?.username
                ?: ""
            val profile = runCatching { UserDefaultPage.fetchAndParse(username) }
                .getOrNull()
                ?: UserDefaultPage.parse(username, "")
            // 解析失败（极端情况：HTML 结构变了）连学号都没有 → 视为未登录，强制重登
            if (profile.studentId.isBlank()) return@withLock false
            _state.value = AuthState.LoggedIn(profile)
            return@withLock true
        }
        // cookie 失效，尝试用保存的凭证静默重登
        val saved = creds.load() ?: return@withLock false
        when (val r = cas.login(saved.username, saved.password)) {
            is CasLoginClient.Result.Success -> {
                onLoginSuccess(saved.username, saved.password)
                true
            }
            is CasLoginClient.Result.Failure -> {
                // 仅当 CAS 明确拒绝认证（密码已改、账号冻结等）时才清凭证；
                // 网络异常 / 教务系统维护 / TLS 失败等瞬时错误保留凭证，下次启动再试 —
                // 用户切到差网络偶尔启动一次不应该丢密码。
                if (r.isAuth) creds.clear()
                false
            }
        }
    }

    suspend fun logout() = authMutex.withLock {
        cas.logout()
        creds.clear()
        clearAllUserDataOnSignOut()
        _state.value = AuthState.LoggedOut
    }

    suspend fun expireSession(message: String = "登录已过期，请重新登录") = authMutex.withLock {
        cas.logout()
        clearAllUserDataOnSignOut()
        _state.value = AuthState.Error(message)
    }

    /**
     * 用本地保存的凭据**静默**重登。不修改 [_state]（用户视角看不到 Loading），
     * 仅用来给业务请求做"过期 → 自动恢复"重放：
     *  - 没保存凭据 / CAS 拒绝 / 网络失败 → 返回 false，调用方应让 SessionExpired 透传
     *  - CAS 明确拒绝认证（[CasLoginClient.Result.Failure.isAuth]==true）→ 清掉凭据，避免下次再用过期密码反复试
     *
     * 与 [login] / [tryRestoreSession] / [logout] 共用 [authMutex] —— 已经在 logout 流程
     * 中的请求不会再触发 reauth；已经在 login 中的请求不会被打断。
     */
    suspend fun tryReauthSilently(): Boolean = authMutex.withLock {
        val saved = creds.load() ?: return@withLock false
        when (val r = cas.login(saved.username, saved.password)) {
            is CasLoginClient.Result.Success -> true
            is CasLoginClient.Result.Failure -> {
                if (r.isAuth) creds.clear()
                false
            }
        }
    }

    /**
     * 把所有"上一用户"留下的派生数据清空：
     *  - 6 个 Repository 单例的内存缓存（schedule/grade/test-grade/exam/announcement/audit）
     *  - 课程周次本地覆盖（CourseOverridesStore）
     *  - 通知已读锚点（AnnouncementReadAnchor）—— 否则下一用户继承上一用户的"最后已读"
     *  - 桌面 widget snapshot 文件 —— 否则锁屏小部件还在显示上一用户的今日课表
     *
     * 注意：调用方已持有 [authMutex]，这里串行执行即可，不会和并发的 login 抢锁。
     */
    private suspend fun clearAllUserDataOnSignOut() {
        ScheduleRepository.instance.clearCache()
        GradeRepository.instance.clearCache()
        TestGradeRepository.instance.clearCache()
        ExamRepository.instance.clearCache()
        MakeupExamRepository.instance.clearCache()
        GraduationAuditRepository.instance.clearCache()
        AnnouncementRepository.instance.clearCache()
        ArticleDetailRepository.instance.clearCache()
        TeacherRepository.instance.clearCache()
        TeacherDetailRepository.instance.clearCache()
        StudentRepository.instance.clearCache()
        StudentDetailRepository.instance.clearCache()
        CourseOverridesStore.clearAll()
        AnnouncementReadAnchor.clear()
        runCatching { WidgetSnapshotStore.clear(appContext) }
    }

    /** 给登录页预填学号用。 */
    fun rememberedUsername(): String? = storage.lastUsername.takeIf { storage.rememberMe && creds.isAvailable() }

    /** 给登录页初始化"下次自动登录" checkbox 状态用，默认 true。 */
    fun isRememberMeOn(): Boolean = storage.rememberMe && creds.isAvailable()

    /** 登录页判断是否能展示「使用已保存的密码登录」入口。 */
    fun hasSavedCredentials(): Boolean = storage.rememberMe && creds.hasCredentials()

    fun updateRememberMe(enabled: Boolean): Boolean {
        val actual = enabled && creds.isAvailable()
        storage.rememberMe = actual
        if (!actual) {
            storage.lastUsername = null
            creds.clear()
        }
        return actual
    }

    companion object {
        @Volatile private var INSTANCE: AuthRepository? = null

        fun init(context: Context): AuthRepository {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: run {
                    val app = context.applicationContext
                    JxnuHttpClient.init(app)
                    val storage = AuthStorage.init(app)
                    val creds = SecureCredentialStore.init(app)
                    val cas = CasLoginClient(app)
                    AuthRepository(app, cas, storage, creds).also { INSTANCE = it }
                }
            }
        }

        val instance: AuthRepository
            get() = INSTANCE
                ?: error("AuthRepository 未初始化，请在 NvzhuanbanApp.onCreate 调用 AuthRepository.init(this)")
    }
}
