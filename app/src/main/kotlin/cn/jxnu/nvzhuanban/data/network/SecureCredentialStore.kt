package cn.jxnu.nvzhuanban.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密凭证存储。仅在用户勾选「记住账号」时存学号 + 密码。
 *
 * 实现：[EncryptedSharedPreferences] + Android Keystore 硬件密钥。
 *  - 主密钥用 AES256_GCM，由系统 Keystore 生成并托管，App 拿不到原始字节
 *  - 文件内容 AES256_GCM 加密，键名 AES256_SIV 加密
 *  - App 被卸载时密钥会一并销毁，重装后即使数据保留也无法解密
 *
 * 设计权衡：
 *  - 原本 [AuthStorage] 注释里说"故意不存密码"——那是 Phase 2 的保守路线
 *  - Phase 3 要做免登录，cookie 总会失效（教务 30 分钟无活动就掉），不存密码就做不到无感
 *  - 用 EncryptedSharedPreferences 是 Google 官方推荐的标准方案，比自己 RSA 加密更安全
 *
 * 注意：root 设备理论上能拿到 keystore 的密钥（如果攻击者已物理控制设备）；
 * 但即便如此，密码也是用 CAS RSA 公钥再加密一次才发出去，泄露风险有限。
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? by lazy { createEncryptedPrefsOrNull(appContext) }

    init {
        clearLegacyPlaintextFallback(appContext)
    }

    /** 保存账号密码，调用前应确认 rememberMe == true。 */
    fun save(username: String, password: String): Boolean {
        val p = prefs ?: return false
        return p.edit()
            .putString(K_USERNAME, username)
            .putString(K_PASSWORD, password)
            .commit()
    }

    /** 同时拿到学号 + 密码；任一为空返回 null。 */
    fun load(): Credentials? {
        val p = prefs ?: return null
        val u = runCatching { p.getString(K_USERNAME, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val password = runCatching { p.getString(K_PASSWORD, null) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Credentials(u, password)
    }

    fun hasCredentials(): Boolean = load() != null

    fun isAvailable(): Boolean = prefs != null

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        clearLegacyPlaintextFallback(appContext)
    }

    data class Credentials(val username: String, val password: String)

    /**
     * 创建（或恢复）加密 prefs。硬件密钥不可用（厂商魔改 ROM / keystore 损坏）时返回 null，
     * 上层会禁用自动登录；绝不退化为明文保存统一身份认证密码。
     */
    private fun createEncryptedPrefsOrNull(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private fun clearLegacyPlaintextFallback(context: Context) {
        context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val FILE_NAME = "jxnu_secure_creds"
        private const val K_USERNAME = "u"
        private const val K_PASSWORD = "p"

        @Volatile private var INSTANCE: SecureCredentialStore? = null

        fun init(context: Context): SecureCredentialStore {
            INSTANCE?.let { return it }
            return synchronized(this) {
                INSTANCE ?: SecureCredentialStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): SecureCredentialStore = INSTANCE
            ?: error("SecureCredentialStore 未初始化")
    }
}
