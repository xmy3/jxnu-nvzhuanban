package cn.jxnu.nvzhuanban.ui.screens.profile

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.AppRelease
import cn.jxnu.nvzhuanban.data.model.UserProfile
import cn.jxnu.nvzhuanban.data.model.formatCredit
import cn.jxnu.nvzhuanban.data.storage.AvatarPrefs
import cn.jxnu.nvzhuanban.data.storage.ThemeMode
import cn.jxnu.nvzhuanban.data.storage.ThemePrefs
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.screens.announcement.openExternalHttpUrl
import cn.jxnu.nvzhuanban.ui.widget.WidgetPinResultReceiver
import cn.jxnu.nvzhuanban.ui.widget.isPinTodayScheduleWidgetSupported
import cn.jxnu.nvzhuanban.ui.widget.openShortcutPermissionSettings
import cn.jxnu.nvzhuanban.ui.widget.pinnedTodayScheduleWidgetCount
import cn.jxnu.nvzhuanban.ui.widget.requestPinTodayScheduleWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit,
    onOpenGrades: () -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenExams: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onOpenPeopleSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    viewModel: ProfileViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestRelease by viewModel.latestRelease.collectAsStateWithLifecycle()
    val isLoggingOut by viewModel.isLoggingOut.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showWidgetDialog by remember { mutableStateOf(false) }
    val themeMode by ThemePrefs.themeMode.collectAsState()
    val dynamicColor by ThemePrefs.dynamicColor.collectAsState()
    val showAvatar by AvatarPrefs.showAvatar.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }

    // 收 ViewModel 的一次性事件（用户主动点「检查更新」的结果），决定弹 Dialog 还是 Snackbar。
    // 静默检查命中走的是 latestRelease StateFlow，跟这里完全独立。
    LaunchedEffect(viewModel) {
        viewModel.checkResult.collect { result ->
            // showSnackbar 会挂起到 Snackbar 消失（Short ≈ 4s）。所有展示都放子协程，
            // 让 collect 立即回到循环收终值；终值到达先 dismiss 掉还挂着的 Checking 提示。
            when (result) {
                UpdateCheckResult.Checking -> launch { snackbarHostState.showSnackbar("正在检查更新…") }
                UpdateCheckResult.Latest -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar("当前已是最新版本 v$versionName") }
                }
                is UpdateCheckResult.Newer -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    showUpdateDialog = true
                }
                is UpdateCheckResult.Failed -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(result.message) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                actions = {
                    RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // StateScaffold 现在会把 modifier 一并应用到 Success 分支，所以只在外层挂一次 padding 即可。
        // 旧代码在内层 LazyColumn 上又 .padding(padding) 一次，会让顶部空出两倍 TopAppBar 高度。
        StateScaffold(
            state = state,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::load,
        ) { data ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    UserCard(
                        user = data.user,
                        showAvatar = showAvatar,
                        enrichStatus = data.enrichStatus,
                        onRetryEnrich = viewModel::retryEnrich,
                    )
                }
                item {
                    GradesEntryCard(
                        cumulativeCredits = data.user.cumulativeCredits,
                        onClick = onOpenGrades,
                    )
                }
                item {
                    ToolsBlock(
                        onOpenExams = onOpenExams,
                        onOpenClassroom = onOpenClassroom,
                        onOpenTrainingPlan = onOpenTrainingPlan,
                        onOpenPeopleSearch = onOpenPeopleSearch,
                        onOpenCalendar = onOpenCalendar,
                        onOpenTheme = { showThemeDialog = true },
                    )
                }
                item {
                    SettingsBlock(
                        showAvatar = showAvatar,
                        versionName = versionName,
                        latestRelease = latestRelease,
                        onAvatarToggle = AvatarPrefs::setShowAvatar,
                        onAddWidgetClick = { showWidgetDialog = true },
                        onAboutClick = { showAboutDialog = true },
                        onCheckUpdate = {
                            if (latestRelease != null) {
                                showUpdateDialog = true
                            } else {
                                viewModel.forceCheck()
                            }
                        },
                        onLogoutClick = { showLogoutConfirm = true },
                    )
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isLoggingOut) showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("将清除本地登录态，需要重新输入账号密码。是否继续？") },
            confirmButton = {
                TextButton(
                    enabled = !isLoggingOut,
                    onClick = {
                        viewModel.logout {
                            showLogoutConfirm = false
                            onLogout()
                        }
                    },
                ) {
                    Text(
                        if (isLoggingOut) "退出中..." else "退出",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLoggingOut,
                    onClick = { showLogoutConfirm = false },
                ) { Text("取消") }
            },
        )
    }

    if (showAboutDialog) {
        AboutDialog(versionName = versionName, onDismiss = { showAboutDialog = false })
    }

    val pendingRelease = latestRelease
    if (showUpdateDialog && pendingRelease != null) {
        UpdateDialog(
            release = pendingRelease,
            onUpdate = {
                openExternalHttpUrl(context, pendingRelease.htmlUrl)
                showUpdateDialog = false
            },
            onSkip = {
                viewModel.skipVersion(pendingRelease.tagName)
                showUpdateDialog = false
            },
            onDismiss = { showUpdateDialog = false },
        )
    }

    if (showThemeDialog) {
        ThemeChoiceDialog(
            current = themeMode,
            dynamicColor = dynamicColor,
            onDynamicColorToggle = ThemePrefs::setDynamicColor,
            onSelect = { ThemePrefs.setMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showWidgetDialog) {
        AddWidgetDialog(onDismiss = { showWidgetDialog = false })
    }
}

@Composable
private fun UserCard(
    user: UserProfile,
    showAvatar: Boolean,
    enrichStatus: EnrichStatus,
    onRetryEnrich: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 学生头像：默认关闭，需在"设置 → 显示学生头像"打开。开启后用 OkHttp + 应用 session
            // cookie 取 jwc /MyControl 上的图；失败回落到 Person 图标
            RemoteJwcImage(
                url = user.avatarUrl?.takeIf { showAvatar },
                contentDescription = stringResource(R.string.cd_avatar),
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                fallback = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${user.studentId} · ${user.grade} 级",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                )
                // 学院/专业/班级 由 ProfileViewModel.enrichFromGrades 后台异步从成绩页补上；
                // 首次进 App 还在加载时这几行可能为空，此时显示 "加载中…" 占位
                Spacer(modifier = Modifier.height(2.dp))
                val collegeMajor = listOf(user.college, user.major)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (collegeMajor.isNotEmpty()) {
                    Text(
                        text = collegeMajor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                if (user.className.isNotBlank()) {
                    Text(
                        text = user.className,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                // 学院/专业/班级 由 ProfileViewModel.enrichFromGrades 后台异步从成绩页补上。
                // 三字段全空时按拉取状态给终态：加载中 → 占位文案；失败 → 可点重试，不再无限转圈。
                if (collegeMajor.isEmpty() && user.className.isBlank()) {
                    when (enrichStatus) {
                        EnrichStatus.Loading -> Text(
                            text = "学院信息加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                        )
                        EnrichStatus.Failed -> Text(
                            text = "学院信息加载失败，点击重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                            // 这是 enrich 失败后屏内唯一的恢复入口：撑到 48dp 最小点按目标并
                            // 播报为按钮，避免一行小字点不中 / TalkBack 听不出可操作
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(role = Role.Button, onClick = onRetryEnrich),
                        )
                        // 加载完成但成绩页确实没给学院信息（极少见，如无成绩的新生）：不显示占位
                        EnrichStatus.Idle -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun GradesEntryCard(
    cumulativeCredits: Float,
    onClick: () -> Unit,
) {
    // 成绩从底栏挪到这里。位置紧贴 UserCard，独占一张 Card —— 视觉上比 ToolsBlock 的小 row
    // 重得多，方便用户一眼看到入口。副标题展示已修学分（cumulativeCredits 由
    // ProfileViewModel.enrichFromGrades 后台从成绩页填上，未到时 fallback 到提示文字）。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.grades_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                val subtitle = if (cumulativeCredits > 0f) {
                    "已修 ${cumulativeCredits.formatCredit()} 学分"
                } else {
                    "查看历年成绩与标准分"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ToolsBlock(
    onOpenExams: () -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onOpenPeopleSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    // 二级功能集合：3 列 × 2 行图标网格，刚好把 6 个入口塞满，不用占位 Spacer。主题色从
    // SettingsBlock 挪过来——它的点击行为是"打开选择 Dialog"，跟其他几个查询入口本质一样，
    // 放一块更顺，也让 SettingsBlock 进一步瘦身。代价是丢了状态副标题（"跟随系统 · 品牌色"），
    // 但 Dialog 打开后就能看到当前选中项。
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            maxItemsInEachRow = 3,
        ) {
            ToolTile(
                icon = Icons.Outlined.Event,
                title = stringResource(R.string.exams_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenExams,
            )
            ToolTile(
                icon = Icons.AutoMirrored.Outlined.ListAlt,
                title = "培养方案",
                modifier = Modifier.weight(1f),
                onClick = onOpenTrainingPlan,
            )
            ToolTile(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.calendar_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenCalendar,
            )
            ToolTile(
                icon = Icons.Outlined.Search,
                title = stringResource(R.string.people_search_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenPeopleSearch,
            )
            ToolTile(
                icon = Icons.Outlined.MeetingRoom,
                title = stringResource(R.string.classroom_title),
                modifier = Modifier.weight(1f),
                onClick = onOpenClassroom,
            )
            ToolTile(
                icon = Icons.Outlined.ColorLens,
                title = stringResource(R.string.profile_theme),
                modifier = Modifier.weight(1f),
                onClick = onOpenTheme,
            )
        }
    }
}

@Composable
private fun ToolTile(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingsBlock(
    showAvatar: Boolean,
    versionName: String,
    latestRelease: AppRelease?,
    onAvatarToggle: (Boolean) -> Unit,
    onAddWidgetClick: () -> Unit,
    onAboutClick: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            SettingsRow(
                icon = Icons.Outlined.Widgets,
                title = "添加桌面小组件",
                subtitle = "把「今日课表」放到手机桌面",
                onClick = onAddWidgetClick,
            )
            SettingsDivider()
            SettingsToggleRow(
                icon = Icons.Outlined.AccountCircle,
                title = "显示学生头像",
                subtitle = "关闭时不向教务系统请求头像",
                checked = showAvatar,
                onCheckedChange = onAvatarToggle,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.profile_about),
                subtitle = "女专办 v$versionName",
                onClick = onAboutClick,
            )
            SettingsDivider()
            // subtitle 三态切换：有新版本时用 primary 色凸显（仿 BadgedBox 视觉但走纯文案，
            // 跟 SettingsBlock 的 row-list 视觉密度更搭）；其他情况只显示当前版本。
            val updateTint = if (latestRelease != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
            SettingsRow(
                icon = Icons.Outlined.SystemUpdate,
                title = "检查更新",
                subtitle = if (latestRelease != null) "发现新版本 v${latestRelease.versionName} · 点击查看"
                else "当前版本 v$versionName",
                tint = updateTint,
                onClick = onCheckUpdate,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = stringResource(R.string.profile_logout),
                tint = MaterialTheme.colorScheme.error,
                onClick = onLogoutClick,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    // toggleable 把整行做成单个 Switch 语义节点（TalkBack 合并播报标签+开关状态），
    // 行内 Switch 的 onCheckedChange 置 null 退化为纯视觉指示器，避免出现第二个焦点
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.SYSTEM -> Icons.Outlined.Brightness6
        ThemeMode.LIGHT -> Icons.Outlined.Brightness7
        ThemeMode.DARK -> Icons.Outlined.Brightness4
    }

@Composable
private fun ThemeChoiceDialog(
    current: ThemeMode,
    dynamicColor: Boolean,
    onDynamicColorToggle: (Boolean) -> Unit,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveDynamic = dynamicColor && dynamicAvailable
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题模式") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    // selectable(Role.RadioButton) 让整行成为单个单选语义节点，
                    // RadioButton onClick 置 null 只做视觉指示（下方动态取色行的 Switch 同理）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == current,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == current,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = mode.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = effectiveDynamic,
                            enabled = dynamicAvailable,
                            role = Role.Switch,
                            onValueChange = onDynamicColorToggle,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ColorLens,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.profile_dynamic_color),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (dynamicAvailable) 1f else 0.5f,
                            ),
                        )
                        Text(
                            text = if (dynamicAvailable) {
                                stringResource(R.string.profile_theme_dynamic)
                            } else {
                                stringResource(R.string.profile_dynamic_color_unavailable)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (dynamicAvailable) 1f else 0.5f,
                            ),
                        )
                    }
                    Switch(
                        checked = effectiveDynamic,
                        onCheckedChange = null,
                        enabled = dynamicAvailable,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/** [AddWidgetDialog] 的阶段。pin 是「发请求 → 系统确认窗 → 桌面回执」的异步链，各阶段文案与按钮不同。 */
private enum class WidgetPinStage {
    /** 初始：功能介绍 + 一键添加按钮，手动步骤兜底。 */
    Ready,

    /** 桌面上已有本小组件，等用户确认是否再加一个。 */
    ConfirmDuplicate,

    /** 请求已被桌面受理，等系统确认窗结果。被静默吞掉（国产桌面未授权）时，本阶段的权限引导是唯一出路。 */
    Requested,

    /** 桌面不支持 pin 请求（或请求被当场拒绝），只能手动添加。 */
    Unsupported,
}

/**
 * 「添加桌面小组件」引导弹窗。`AppWidgetManager.requestPinAppWidget` 返回 true 只代表
 * 桌面**受理**了请求：部分国产系统（小米 / 华为等）在未授予「桌面快捷方式」权限时会把
 * 系统确认窗静默吞掉，用户点了「一键添加」却毫无动静。因此请求发出后弹窗**不关闭**，
 * 切到 [WidgetPinStage.Requested] 常驻「去开启权限 + 重试 + 手动步骤」三条出路；真正
 * 添加成功由 [WidgetPinResultReceiver] 的回执驱动——Toast 反馈 + 本弹窗自动关闭。
 */
@Composable
private fun AddWidgetDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var stage by remember {
        mutableStateOf(
            if (isPinTodayScheduleWidgetSupported(context)) WidgetPinStage.Ready
            else WidgetPinStage.Unsupported,
        )
    }
    // 发请求前记下的成功回执基线；Requested 阶段计数一旦越过基线（桌面确认添加成功）就自动收尾
    var successBaseline by remember { mutableIntStateOf(0) }

    if (stage == WidgetPinStage.Requested) {
        LaunchedEffect(successBaseline) {
            WidgetPinResultReceiver.successCount.first { it > successBaseline }
            onDismiss()
        }
    }

    val requestPin: () -> Unit = {
        successBaseline = WidgetPinResultReceiver.successCount.value
        stage = if (requestPinTodayScheduleWidget(context)) WidgetPinStage.Requested
        else WidgetPinStage.Unsupported
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加桌面小组件") },
        text = {
            // M3 AlertDialog 的 text 槽自身不滚动，超高内容会被直接裁切——大字体 / 小屏下
            // Requested 阶段的「去开启权限」按钮可能被裁到点不到，必须自己加 verticalScroll
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 四个阶段的首句共用同一个 Text 节点并挂 liveRegion：stage 原地切换（弹窗
                // 不重开）时文本变化会被 TalkBack 自动播报，读屏用户才知道引导内容更新了
                Text(
                    text = when (stage) {
                        WidgetPinStage.Ready ->
                            "「今日课表」小组件可在手机桌面直接查看当天课程和下一节课倒计时，无需打开 App。"
                        WidgetPinStage.ConfirmDuplicate ->
                            "桌面上已经有「今日课表」小组件了，还要再添加一个吗？"
                        WidgetPinStage.Requested -> "已向桌面发出添加请求："
                        WidgetPinStage.Unsupported -> "当前桌面不支持一键添加，请手动添加："
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stage == WidgetPinStage.Unsupported) MaterialTheme.colorScheme.primary
                    else Color.Unspecified,
                    fontWeight = when (stage) {
                        WidgetPinStage.Requested, WidgetPinStage.Unsupported -> FontWeight.SemiBold
                        else -> null
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                when (stage) {
                    WidgetPinStage.Ready -> {
                        Spacer(Modifier.height(12.dp))
                        ManualAddSteps()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "部分手机需先允许本应用「桌面快捷方式」权限才能一键添加，点了没反应时按弹窗提示处理即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    WidgetPinStage.ConfirmDuplicate -> Unit

                    WidgetPinStage.Requested -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "屏幕上弹出了确认窗的话，点「添加」即可，成功后这里会自动关闭。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "如果没有任何反应，是系统拦截了请求（小米、华为等手机常见）：点下方「去开启权限」允许本应用「桌面快捷方式」，回来再点「重试」；或按下面的步骤手动添加。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { openShortcutPermissionSettings(context) },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("去开启权限") }
                        ManualAddSteps()
                    }

                    WidgetPinStage.Unsupported -> {
                        Spacer(Modifier.height(4.dp))
                        ManualAddSteps()
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                WidgetPinStage.Ready -> TextButton(
                    onClick = {
                        if (pinnedTodayScheduleWidgetCount(context) > 0) {
                            stage = WidgetPinStage.ConfirmDuplicate
                        } else {
                            requestPin()
                        }
                    },
                ) { Text("一键添加") }

                WidgetPinStage.ConfirmDuplicate -> TextButton(onClick = requestPin) { Text("仍要添加") }
                WidgetPinStage.Requested -> TextButton(onClick = requestPin) { Text("重试") }
                WidgetPinStage.Unsupported -> TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            when (stage) {
                WidgetPinStage.Ready, WidgetPinStage.ConfirmDuplicate ->
                    TextButton(onClick = onDismiss) { Text("取消") }

                WidgetPinStage.Requested -> TextButton(onClick = onDismiss) { Text("关闭") }
                WidgetPinStage.Unsupported -> Unit
            }
        },
    )
}

/** 手动添加小组件的通用步骤，Ready / Requested / Unsupported 三个阶段都常驻展示。 */
@Composable
private fun ManualAddSteps() {
    Text(
        "手动添加：长按桌面空白处 → 选择「窗口小工具（小部件）」→ 找到「女专办」拖到桌面。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AboutDialog(
    versionName: String,
    onDismiss: () -> Unit,
) {
    var privacyExpanded by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column {
                Text(
                    "女专办",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                val primaryColor = MaterialTheme.colorScheme.primary
                val taglineText = remember(primaryColor) {
                    buildAnnotatedString {
                        append("江西师范大学非官方教务客户端，仿「")
                        withLink(
                            LinkAnnotation.Url(
                                url = "https://github.com/Reqwey/MySHSMU",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = primaryColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            append("酱紫办")
                        }
                        append("」。本应用与江西师范大学及其教务处无任何隶属或合作关系，仅供个人学习查询使用。")
                    }
                }
                Text(
                    taglineText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "所有数据仅取自学校教务系统并保存在本机，App 不上传、不收集任何用户数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { privacyExpanded = !privacyExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(if (privacyExpanded) "收起隐私说明" else "查看完整隐私说明")
                }
                if (privacyExpanded) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    PrivacySection(
                        title = "本地保存",
                        body = "登录 Cookie、自动登录凭据、课表周次修正、主题与头像偏好、通知已读位置、桌面小组件快照，全部仅存于本机。",
                    )
                    Spacer(Modifier.height(8.dp))
                    PrivacySection(
                        title = "加密凭据",
                        body = "仅当勾选「下次自动登录」时才会保存账号密码，并通过 Android Keystore + EncryptedSharedPreferences 加密。",
                    )
                    Spacer(Modifier.height(8.dp))
                    PrivacySection(
                        title = "头像请求",
                        body = "学生头像默认不加载；仅在打开「显示学生头像」后，才会向教务系统请求头像。",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/**
 * 「发现新版本」对话框。仿 [AboutDialog] 的 `text + 底部 TextButton` 排版 —— "跳过此版本"
 * 不挤进 confirm/dismiss 槽位（Material3 AlertDialog 标准是两按钮），而是放在 text 底部
 * 当 secondary action，跟 AboutDialog 的"查看完整隐私说明"位置一致。
 *
 * Release notes 不做 Markdown 渲染（项目无 markdown 解析依赖），直接展示原文。多数 release
 * 的 `### 标题` / `- 列表项` 字面量虽然不好看但仍可读，且不掺第三方依赖。
 */
@Composable
private fun UpdateDialog(
    release: AppRelease,
    onUpdate: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${release.versionName}") },
        text = {
            Column {
                if (release.publishedAt.isNotBlank()) {
                    Text(
                        "发布于 ${release.publishedAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (release.body.isNotBlank()) {
                    Text(
                        release.body,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(
                    onClick = onSkip,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("跳过此版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("立即更新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
