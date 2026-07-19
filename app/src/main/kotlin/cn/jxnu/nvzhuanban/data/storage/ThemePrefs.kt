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
 * 课表课程卡的配色方案。这里只定枚举与持久化标识；每个方案的具体色板（12 色）在 UI 层
 * [cn.jxnu.nvzhuanban.ui.screens.schedule] 的 SchedulePalettes.kt 里定义——storage 不依赖 Compose。
 *
 * 所有方案共享同一约束：色板需保证其上白色 9sp 小字对比度 ≥ 4.5:1（见 SchedulePalettes.kt
 * 顶部注释与 SchedulePaletteContrastTest）。
 */
enum class SchedulePalette(val storageValue: String) {
    CLASSIC("classic"),
    MORANDI("morandi"),
    OCEAN("ocean"),
    SUNSET("sunset"),
    FOREST("forest");

    companion object {
        fun fromStorage(raw: String?): SchedulePalette =
            entries.firstOrNull { it.storageValue == raw } ?: CLASSIC
    }
}

/**
 * 通知详情正文的字号档位。[scale] 由详情页乘到 LocalDensity.fontScale 上（叠加在系统字体
 * 缩放之上），整篇正文（标题 / 段落 / 表格 / 附件名）成比例缩放。
 *
 * 与 [ThemeMode] / [SchedulePalette] 同为外观偏好：登出**不**清（换账号不影响阅读习惯）。
 */
enum class ArticleFontSize(val storageValue: String, val scale: Float) {
    SMALL("small", 0.85f),
    DEFAULT("default", 1f),
    LARGE("large", 1.15f),
    XLARGE("xlarge", 1.3f);

    companion object {
        fun fromStorage(raw: String?): ArticleFontSize =
            entries.firstOrNull { it.storageValue == raw } ?: DEFAULT
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
    private const val KEY_SCHEDULE_PALETTE = "schedule_palette"
    private const val KEY_ARTICLE_FONT_SIZE = "article_font_size"

    private lateinit var sp: SharedPreferences
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Material You 动态取色；默认开，Theme.kt 内部再按 SDK 版本兜底
    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    // 课表配色方案；跟 themeMode 一样是外观偏好，登出**不**清（换账号不影响审美选择）
    private val _schedulePalette = MutableStateFlow(SchedulePalette.CLASSIC)
    val schedulePalette: StateFlow<SchedulePalette> = _schedulePalette.asStateFlow()

    // 通知详情正文字号；外观偏好，登出不清
    private val _articleFontSize = MutableStateFlow(ArticleFontSize.DEFAULT)
    val articleFontSize: StateFlow<ArticleFontSize> = _articleFontSize.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _themeMode.value = ThemeMode.fromStorage(sp.getString(KEY_MODE, null))
        _dynamicColor.value = sp.getBoolean(KEY_DYNAMIC, true)
        _schedulePalette.value = SchedulePalette.fromStorage(sp.getString(KEY_SCHEDULE_PALETTE, null))
        _articleFontSize.value = ArticleFontSize.fromStorage(sp.getString(KEY_ARTICLE_FONT_SIZE, null))
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

    fun setSchedulePalette(palette: SchedulePalette) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_SCHEDULE_PALETTE, palette.storageValue).apply()
        _schedulePalette.value = palette
    }

    fun setArticleFontSize(size: ArticleFontSize) {
        if (!::sp.isInitialized) return
        sp.edit().putString(KEY_ARTICLE_FONT_SIZE, size.storageValue).apply()
        _articleFontSize.value = size
    }
}
