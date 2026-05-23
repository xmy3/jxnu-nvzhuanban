package cn.jxnu.nvzhuanban.data.network

import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 业务 Repository 在拿到 [JwcError.SessionExpired] 时调 [tryReauthSilently]。
 * 成功 → 重放原 HTTP 请求；失败 → 用 [SessionEvents.notifyExpired] 把用户踢回登录页。
 *
 * [reauthMutex] 把短时间内的并发 reauth 合并为一次：成绩 / 课表 / 通知同时触发 SessionExpired
 * 时只跑一次 CAS 登录，其余调用方等待结果。
 */
object SessionRecovery {
    private val reauthMutex = Mutex()

    /**
     * @return true 表示已用本地保存的凭据完成 CAS 重登，调用方可重放原请求；
     *         false 表示没保存凭据 / CAS 拒绝 / 网络失败，应让 SessionExpired 透传到用户。
     */
    suspend fun tryReauthSilently(): Boolean = reauthMutex.withLock {
        AuthRepository.instance.tryReauthSilently()
    }
}
