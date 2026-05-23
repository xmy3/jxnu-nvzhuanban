package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import androidx.core.content.edit

/**
 * 轻量 KV 存储：学号、installation UUID、记住账号偏好。
 *
 * 故意**不存密码**——
 * - 真要做免密登录，应该用 Android Keystore 加密存 token/cookie，不存明文密码
 * - 现阶段密码每次都让用户重新输；登录成功后靠 cookie 维持会话
 *
 * 用 SharedPreferences 而非 DataStore：
 * - 同步读取（DataStore 需要协程，CookieJar 不方便）
 * - 数据量极小（< 200 字节）
 * - 不需要事务性多键更新
 */
class AuthStorage(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jxnu_auth", Context.MODE_PRIVATE)

    /** 上次登录的学号，用于自动回填登录框。 */
    var lastUsername: String?
        get() = prefs.getString(K_USERNAME, null)
        set(v) = prefs.edit { putString(K_USERNAME, v) }

    /** 用户是否勾选了"记住账号"。 */
    var rememberMe: Boolean
        get() = prefs.getBoolean(K_REMEMBER_ME, true)
        set(v) = prefs.edit { putBoolean(K_REMEMBER_ME, v) }

    /** Installation UUID，首次访问时生成并持久化。 */
    fun installUuid(): String {
        prefs.getString(K_INSTALL_UUID, null)?.let { return it }
        val fresh = DeviceFingerprint.newInstallUuid()
        prefs.edit { putString(K_INSTALL_UUID, fresh) }
        return fresh
    }

    /** 完全清空，用于退出登录。 */
    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val K_USERNAME = "last_username"
        private const val K_REMEMBER_ME = "remember_me"
        private const val K_INSTALL_UUID = "install_uuid"

        @Volatile private var INSTANCE: AuthStorage? = null

        fun init(context: Context): AuthStorage {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: AuthStorage(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): AuthStorage = INSTANCE
            ?: error("AuthStorage 未初始化，请在 Application.onCreate 调用 AuthStorage.init(this)")
    }
}
