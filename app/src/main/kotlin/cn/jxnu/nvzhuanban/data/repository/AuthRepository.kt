package cn.jxnu.nvzhuanban.data.repository

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.model.hasPlaceholderName
import cn.jxnu.nvzhuanban.data.network.AuthStorage
import cn.jxnu.nvzhuanban.data.network.CasLoginClient
import cn.jxnu.nvzhuanban.data.network.JxnuHttpClient
import cn.jxnu.nvzhuanban.data.network.SecureCredentialStore
import cn.jxnu.nvzhuanban.data.network.pages.UserDefaultPage
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import cn.jxnu.nvzhuanban.data.storage.PeopleSearchHistoryStore
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import cn.jxnu.nvzhuanban.ui.components.clearDecodedImageCache
import cn.jxnu.nvzhuanban.ui.widget.TodayScheduleWidget
import cn.jxnu.nvzhuanban.ui.widget.WidgetUpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /**
     * 自动重登节流。手动登录不受影响（用户明确意图），仅自动通道（[tryRestoreSession] /
     * [tryReauthSilently]）受指数退避保护：避免本地保存的旧密码反复打 CAS 把账号锁死。
     *
     * 触发场景：用户在网页端改了密码 / 学校强制改密 / CAS 临时拒绝 → 旧凭证不再可用。
     * 第一次失败如果 [CasLoginClient.Result.Failure.isAuth] 为 true，凭证已被清掉，
     * 不会再有第二次。但 isAuth=false 的瞬时失败（网络抖动 / 5xx）凭证保留，下次冷启动
     * 还会重试 —— 这里的节流就是兜底这条路径。
     */
    private val autoLoginThrottle = AutoLoginThrottle()

    class LoginException(message: String) : RuntimeException(message)

    suspend fun login(username: String, password: String, captcha: String = "") = authMutex.withLock {
        _state.value = AuthState.Loading
        when (val r = cas.login(username.trim(), password, captcha.trim())) {
            is CasLoginClient.Result.Success -> {
                // 手动登录成功 → 重置自动通道节流，让后续冷启动 silent retry 立即可用
                autoLoginThrottle.recordSuccess()
                onLoginSuccess(username.trim(), password)
            }
            is CasLoginClient.Result.Failure -> {
                // 手动登录失败不阻挡用户重试，但**也算进自动通道的失败计数**：
                // 否则用户在登录页连点 5 次错密码，下次冷启动 silent retry 仍会无脑试一次。
                autoLoginThrottle.recordFailure()
                _state.value = AuthState.Error(r.message)
                throw LoginException(r.message)
            }
        }
    }

    private suspend fun onLoginSuccess(username: String, password: String) {
        // 换号登录检测：上一个成功登录的用户和这次不同，说明设备易主/换账号，
        // 上一用户的本地派生数据（周次覆盖 / 已读锚点 / widget 快照）必须清掉。
        // 同一用户重登（会话过期后最常见的路径）则全部保留。
        val previousUser = storage.lastLoggedUser
        if (previousUser != null && previousUser != username) {
            // 清理链路含 SharedPreferences / widget 快照文件删除，切 IO 防主线程卡顿
            withContext(Dispatchers.IO) { clearAllUserDataOnSignOut() }
        }
        storage.lastLoggedUser = username
        // 凭证落盘是 Tink 加密 + fsync 的同步写（save 用 commit 才能拿到成败位），必须切 IO，
        // 否则手动登录这一下会在主线程（viewModelScope 默认 Main）卡顿，低端机上是 ANR 隐患。
        withContext(Dispatchers.IO) {
            if (storage.rememberMe) {
                val saved = creds.save(username, password)
                if (saved) {
                    storage.lastUsername = username
                } else {
                    // EncryptedSharedPreferences 写失败（厂商魔改 ROM / keystore 损坏 / 设备空间满）
                    // 之前的策略是静默关 rememberMe，用户视角"登录成功"但下次启动还得重新输——
                    // 体验非常古怪。这里把一次性警告塞到 lastLoginWarning，由登录页 ViewModel
                    // 在 submit 成功后取走显示。
                    storage.rememberMe = false
                    storage.lastUsername = null
                    creds.clear()
                    lastLoginWarning = "本机无法安全保存密码，已关闭自动登录"
                }
            } else {
                creds.clear()
            }
        }
        WidgetSnapshotStore.resumeWrites()
        val profile = runCatching { UserDefaultPage.fetchAndParse(username) }
            .getOrNull()
            ?: UserDefaultPage.parse(username, "")
        _state.value = AuthState.LoggedIn(profile)
    }

    /**
     * 一次性的登录侧警告（不是 Error —— 登录本身成功了，只是有衍生异常需要告诉用户）。
     * [LoginViewModel] 在 submit 成功后调 [consumeLastLoginWarning] 取走并清零。
     */
    @Volatile private var lastLoginWarning: String? = null

    fun consumeLastLoginWarning(): String? {
        val w = lastLoginWarning
        lastLoginWarning = null
        return w
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
            // cookie 直连路径同样要记录/比对「上一个登录用户」——否则一直靠 cookie 续命的
            // 存量用户 lastLoggedUser 永远是 null，换号检测失效。JXNU 的 CAS 登录名就是
            // 学号，与 onLoginSuccess 记录的 username 同一口径。
            val previousUser = storage.lastLoggedUser
            if (previousUser != null && previousUser != profile.studentId) {
                withContext(Dispatchers.IO) { clearAllUserDataOnSignOut() }
            }
            storage.lastLoggedUser = profile.studentId
            // cookie 直接有效本身就是"自动通道成功"，重置 throttle
            autoLoginThrottle.recordSuccess()
            _state.value = AuthState.LoggedIn(profile)
            return@withLock true
        }
        // cookie 失效，尝试用保存的凭证静默重登
        val saved = creds.load() ?: return@withLock false
        // 自动通道节流：上次失败后还在冷却窗口内则跳过，避免旧密码反复打 CAS。
        // 用户可手动从登录页登录（不受 throttle 限制）。
        if (autoLoginThrottle.shouldSkip()) return@withLock false
        when (val r = cas.login(saved.username, saved.password)) {
            is CasLoginClient.Result.Success -> {
                autoLoginThrottle.recordSuccess()
                onLoginSuccess(saved.username, saved.password)
                true
            }
            is CasLoginClient.Result.Failure -> {
                autoLoginThrottle.recordFailure()
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
        // 只清内存缓存（会话相关、可重新拉取），**不**清 CourseOverridesStore / 已读锚点 /
        // widget 快照这类同账号的本地数据 —— 会话过期是可自愈事件，预期同一用户马上重登
        // （凭证也特意保留了），不应把它当成主动登出销毁用户手工维护的数据。
        // 换号场景的清理由 onLoginSuccess 的换号检测兜底。
        clearRepositoryCaches()
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
        // 自动通道节流：与 tryRestoreSession 共享同一计数 / 冷却窗口
        if (autoLoginThrottle.shouldSkip()) return@withLock false
        when (val r = cas.login(saved.username, saved.password)) {
            is CasLoginClient.Result.Success -> {
                autoLoginThrottle.recordSuccess()
                true
            }
            is CasLoginClient.Result.Failure -> {
                autoLoginThrottle.recordFailure()
                if (r.isAuth) creds.clear()
                false
            }
        }
    }

    /**
     * 重新拉一次用户首页，刷新基础 profile（姓名 / 学号 / 年级 / 头像 URL）。
     *
     * 触发场景：首次登录或会话恢复时弱网拉首页失败 → [onLoginSuccess] / [tryRestoreSession]
     * 回退到 `parse(username, "")` 空 HTML → 姓名退化成占位（学号仍来自传入参数），且这条
     * 降级 profile 被钉进 [_state] 后整个会话不再刷新。[cn.jxnu.nvzhuanban.ui.screens.profile.ProfileViewModel]
     * 进页面时若发现姓名仍是占位，调本方法在网络恢复后自愈，不必等用户重启 / 重登。
     *
     * 仅当前为 [AuthState.LoggedIn] 时有效。成功且姓名非占位 → 写回 [_state] 并返回新 profile；
     * 失败 / 仍占位 → 返回 null，保留原 profile（不拿更差的数据覆盖已有的）。
     *
     * 走 [UserDefaultPage.fetchAndParse]（内部 raw getHtml，不触发 reauth），所以持有
     * [authMutex] 期间不会经 [cn.jxnu.nvzhuanban.data.network.SessionRecovery] 重入本类锁。
     */
    suspend fun refreshProfile(): UserProfile? = authMutex.withLock {
        val current = (_state.value as? AuthState.LoggedIn)?.profile ?: return@withLock null
        val refreshed = runCatching { UserDefaultPage.fetchAndParse(current.studentId) }
            .getOrNull()
            ?: return@withLock null
        if (refreshed.hasPlaceholderName) return@withLock null
        _state.value = AuthState.LoggedIn(refreshed)
        refreshed
    }

    /**
     * 把所有"上一用户"留下的派生数据清空：
     *  - 6 个 Repository 单例的内存缓存（schedule/grade/test-grade/exam/announcement/audit）
     *  - 课程周次本地覆盖（CourseOverridesStore）
     *  - 通知已读锚点（AnnouncementReadAnchor）—— 否则下一用户继承上一用户的"最后已读"
     *  - 桌面 widget snapshot 文件 —— 否则锁屏小部件还在显示上一用户的今日课表
     *  - 图片缓存（内存解码位图 + OkHttp 磁盘缓存）—— 否则上一用户的头像 / 师生照片跨账号残留
     *
     * 注意：调用方已持有 [authMutex]，这里串行执行即可，不会和并发的 login 抢锁。
     */
    private suspend fun clearAllUserDataOnSignOut() {
        clearRepositoryCaches()
        CourseOverridesStore.clearAll()
        PeopleSearchHistoryStore.clearAll()
        AnnouncementReadAnchor.clear()
        clearWidgetSnapshotOnSignOut()
        clearDecodedImageCache()
        JxnuHttpClient.get().clearHttpCache()
    }

    /** 仅清各 Repository 单例的内存缓存 —— 会话过期时用（数据可在重登后重新拉取）。 */
    private suspend fun clearRepositoryCaches() {
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
    }

    private suspend fun clearWidgetSnapshotOnSignOut() {
        WidgetSnapshotStore.clear(appContext)
        WidgetUpdateScheduler.cancel(appContext)
        runCatching {
            val mgr = GlanceAppWidgetManager(appContext)
            if (mgr.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                TodayScheduleWidget().updateAll(appContext)
            }
        }
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

/**
 * 自动登录通道的失败节流。仅 [AuthRepository.tryRestoreSession] / [AuthRepository.tryReauthSilently]
 * 调用，**手动 [AuthRepository.login] 不受冷却阻挡**（用户明确意图）但会贡献到失败计数。
 *
 * 退避表（连续失败次数 → 冷却时间）：
 *  - 1 次：30 秒
 *  - 2 次：2 分钟
 *  - 3 次：5 分钟
 *  - 4 次：15 分钟
 *  - ≥5 次：30 分钟
 *
 * 任何一次成功（cookie 自验有效 / CAS login 成功）清零计数。计数仅活在进程里——
 * App 被杀重启即重置，避免持久化逻辑引来更难调的状态。冷启动场景下：第一次冷启动失败 →
 * 进程退出 → 第二次冷启动会再试一次（不受节流阻挡）。这是有意为之：用户手动重启 App
 * 视为"用户已知问题、希望重试"，比墨守 30 分钟冷却更合用户预期。
 *
 * 真正要拦的是**单进程内**的高频自动尝试：业务请求触发的 SessionExpired → reauth 死循环。
 */
private class AutoLoginThrottle {
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var consecutiveFailures: Int = 0

    fun shouldSkip(): Boolean {
        val n = consecutiveFailures
        if (n == 0) return false
        val elapsed = System.currentTimeMillis() - lastFailureAt
        return elapsed < cooldownMs(n)
    }

    fun recordFailure() {
        lastFailureAt = System.currentTimeMillis()
        // 限上界，避免 32 次失败后变成天文数字（虽然 cooldownMs 已经 capped）
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_TRACKED)
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        lastFailureAt = 0L
    }

    private fun cooldownMs(n: Int): Long = when (n) {
        0 -> 0L
        1 -> 30_000L
        2 -> 2 * 60_000L
        3 -> 5 * 60_000L
        4 -> 15 * 60_000L
        else -> 30 * 60_000L
    }

    companion object {
        private const val MAX_TRACKED = 8
    }
}
