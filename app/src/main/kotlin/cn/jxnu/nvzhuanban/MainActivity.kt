package cn.jxnu.nvzhuanban

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.ui.navigation.AppNav
import cn.jxnu.nvzhuanban.ui.theme.NvzhuanbanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen 必须在 super.onCreate 之前 install，否则 manifest 主题不被识别
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 等 Application 启动的会话恢复任务完成再放走 splash。
        // 任务在 NvzhuanbanApp.onCreate 已经在 Dispatchers.IO 里启动；
        // 用 lifecycleScope 而非裸 CoroutineScope，Activity 销毁时自动取消，避免协程泄漏。
        val app = application as NvzhuanbanApp
        var isReady = false
        splash.setKeepOnScreenCondition { !isReady }
        lifecycleScope.launch {
            runCatching { app.sessionRestore.await() }
            isReady = true
        }

        enableEdgeToEdge()
        // 全局静音：禁用系统 View 点击/缩放等触发的提示音；震感由系统默认行为保留
        window.decorView.isSoundEffectsEnabled = false
        setContent {
            val themeMode by ThemePrefs.themeMode.collectAsState()
            val dynamicColor by ThemePrefs.dynamicColor.collectAsState()
            NvzhuanbanTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                AppNav()
            }
        }
    }
}
