package cn.jxnu.nvzhuanban.data.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式。SYSTEM 跟随系统暗色设置；LIGHT/DARK 强制覆盖。
 *
 * 与 [ThemePrefs] 配套使用。UI 层通过 [ThemePrefs.themeMode] 订阅，[ThemePrefs.setMode] 写入。
 */
enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(raw: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/**
 * 单例持久化主题选择。
 *
 * 用 SharedPreferences 而非 DataStore，理由与 [cn.jxnu.nvzhuanban.data.network.AuthStorage] 一致：
 * 同步读取，Theme composable 不需要再等异步初始化。
 */
object ThemePrefs {

    private const val PREF_NAME = "theme_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_DYNAMIC = "dynamic_color"

    private lateinit var sp: SharedPreferences
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Material You 动态取色；默认开，Theme.kt 内部再按 SDK 版本兜底
    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _themeMode.value = ThemeMode.fromStorage(sp.getString(KEY_MODE, null))
        _dynamicColor.value = sp.getBoolean(KEY_DYNAMIC, true)
    }

    fun setMode(mode: ThemeMode) {
        // init guard：极端时序下（如 widget receiver 进程 / ContentProvider 抢先调用，
        // 或单测漏 init），避免 lateinit 异常导致整页崩。和 [clearAll] 的处理一致。
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_MODE, mode.storageValue).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        if (!::sp.isInitialized) return
        sp.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }
}
