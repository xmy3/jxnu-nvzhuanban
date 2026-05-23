package cn.jxnu.nvzhuanban

import android.app.Application
import cn.jxnu.nvzhuanban.data.repository.AnnouncementRepository
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementReadAnchor
import cn.jxnu.nvzhuanban.data.storage.AvatarPrefs
import cn.jxnu.nvzhuanban.data.storage.CourseOverridesStore
import cn.jxnu.nvzhuanban.data.storage.ScheduleHeightPrefs
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NvzhuanbanApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 登录态恢复任务，MainActivity 的 splash 用它做"保持等待"的信号。
     * 结果 = true → 已恢复为 LoggedIn；false → 需要让用户走登录页。
     */
    lateinit var sessionRestore: Deferred<Boolean>
        private set

    override fun onCreate() {
        super.onCreate()

        ThemePrefs.init(this)
        AvatarPrefs.init(this)
        ScheduleHeightPrefs.init(this)
        CourseOverridesStore.init(this)
        AnnouncementReadAnchor.init(this)
        val auth = AuthRepository.init(this)
        val deferred = CompletableDeferred<Boolean>()
        sessionRestore = deferred

        appScope.launch {
            val ok = runCatching { auth.tryRestoreSession() }.getOrDefault(false)
            deferred.complete(ok)
        }

        // 通知页是公开页（无需登录），可以与登录态恢复并发跑。
        // 目的是让底部导航的小红点能在用户进入通知 tab 前就感知到新通知。
        // 失败（无网/超时）静默忽略，UI 不会显示红点。
        appScope.launch {
            runCatching { AnnouncementRepository.instance.fetchAll(page = 1) }
        }
    }
}
