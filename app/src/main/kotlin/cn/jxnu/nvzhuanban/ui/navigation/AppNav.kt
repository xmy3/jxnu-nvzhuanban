package cn.jxnu.nvzhuanban.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.jxnu.nvzhuanban.NvzhuanbanApp
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.AuthState
import cn.jxnu.nvzhuanban.data.network.SessionEvents
import cn.jxnu.nvzhuanban.data.repository.AuthRepository
import cn.jxnu.nvzhuanban.data.storage.AnnouncementUnreadState
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.announcement.AnnouncementScreen
import cn.jxnu.nvzhuanban.ui.screens.calendar.CalendarScreen
import cn.jxnu.nvzhuanban.ui.screens.classroom.ClassroomScreen
import cn.jxnu.nvzhuanban.ui.screens.exams.ExamsScreen
import cn.jxnu.nvzhuanban.ui.screens.grades.GradesScreen
import cn.jxnu.nvzhuanban.ui.screens.login.LoginScreen
import cn.jxnu.nvzhuanban.ui.screens.peoplesearch.PeopleSearchScreen
import cn.jxnu.nvzhuanban.ui.screens.peoplesearch.PersonResult
import cn.jxnu.nvzhuanban.ui.screens.profile.ProfileScreen
import cn.jxnu.nvzhuanban.ui.screens.schedule.ScheduleScreen
import cn.jxnu.nvzhuanban.ui.screens.students.StudentDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.teachers.TeacherDetailScreen
import cn.jxnu.nvzhuanban.ui.screens.trainingplan.TrainingPlanScreen
import cn.jxnu.nvzhuanban.ui.screens.userschedule.UserScheduleScreen

object Routes {
    const val LOGIN = "login"
    const val SCHEDULE = "schedule"
    const val GRADES = "grades"
    const val EXAMS = "exams"
    const val ANNOUNCEMENT = "announcement"
    const val CLASSROOM = "classroom"
    const val TRAINING_PLAN = "training_plan"
    const val CALENDAR = "calendar"
    /** 教工 + 学生合并后的统一查询入口；进入后由顶部 chip 切换。 */
    const val PEOPLE_SEARCH = "people_search"
    const val PROFILE = "profile"

    /** 带 articleId 参数的详情页路由；用 [announcementDetail] 构造实例。 */
    const val ANNOUNCEMENT_DETAIL = "announcement_detail/{articleId}"
    fun announcementDetail(articleId: String) = "announcement_detail/$articleId"

    /** 教工详情 / 教工课表：userNum 是教号 base64，可能含 `+ /`，需要 [android.net.Uri.encode]。 */
    const val TEACHER_DETAIL = "teacher_detail/{userNum}/{name}"
    fun teacherDetail(userNum: String, name: String) =
        "teacher_detail/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"

    const val TEACHER_SCHEDULE = "teacher_schedule/{userNum}/{name}"
    fun teacherSchedule(userNum: String, name: String) =
        "teacher_schedule/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"

    /**
     * 学生详情 / 学生课表：userNum 是学号 base64（教务网常带 `==` 结尾），
     * 多出一个 `department` 路径段，把检索列表里抓到的「所在单位」带过来——详情页 HTML
     * 自己不提供这个字段。
     */
    const val STUDENT_DETAIL = "student_detail/{userNum}/{name}/{department}"
    fun studentDetail(userNum: String, name: String, department: String) =
        "student_detail/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}/${android.net.Uri.encode(department.ifEmpty { "-" })}"

    const val STUDENT_SCHEDULE = "student_schedule/{userNum}/{name}"
    fun studentSchedule(userNum: String, name: String) =
        "student_schedule/${android.net.Uri.encode(userNum)}/${android.net.Uri.encode(name)}"
}

data class TabSpec(
    val route: String,
    val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val TABS = listOf(
    TabSpec(Routes.SCHEDULE, R.string.nav_schedule, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    TabSpec(Routes.ANNOUNCEMENT, R.string.nav_announcement, Icons.Outlined.Notifications, Icons.Filled.Notifications),
    TabSpec(Routes.PROFILE, R.string.nav_profile, Icons.Outlined.Person, Icons.Filled.Person),
)

private val mainRouteSet = TABS.map { it.route }.toSet()

@Composable
fun AppNav() {
    // Splash 限时 800ms 兜底放行（见 MainActivity.SPLASH_MAX_WAIT_MS）—— 如果 sessionRestore
    // 这时还没回来（差网络冷启动常见），AppNav 自己显示 loading 占位继续等，**不**进入
    // NavHost。这是必须的：下面的 [startDestination] 用 `remember { ... }` 一次性计算，
    // 此时 AuthState 默认是 LoggedOut，会被锁死到登录页，sessionRestore 后续返回 LoggedIn
    // 也不会再切回 SCHEDULE。等到 sessionReady=true 再首次组合 NavHost 就避开了这个 race。
    val app = LocalContext.current.applicationContext as NvzhuanbanApp
    val sessionReady by produceState(initialValue = app.sessionRestore.isCompleted) {
        if (!value) {
            runCatching { app.sessionRestore.await() }
            value = true
        }
    }
    if (!sessionReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val nav = rememberNavController()
    val authRepo = AuthRepository.instance
    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route
    val showBottomBar = currentRoute in mainRouteSet
    val sessionExpiredSignal by SessionEvents.expiredSignal.collectAsStateWithLifecycle()
    val authState by authRepo.state.collectAsStateWithLifecycle()
    val hasAnnouncementUnread by AnnouncementUnreadState.hasUnread
        .collectAsStateWithLifecycle(initialValue = false)

    // 启动时（Splash 屏 + 上面 sessionReady gate 已经等过 tryRestoreSession）：
    //   已登录 → 直接进课表
    //   未登录 → 进登录页
    // remember 确保 startDestination 只在首次 Composition 计算一次，避免回退栈被搅乱
    val startDestination = remember {
        when (AuthRepository.instance.state.value) {
            is AuthState.LoggedIn -> Routes.SCHEDULE
            else -> Routes.LOGIN
        }
    }

    LaunchedEffect(sessionExpiredSignal) {
        if (sessionExpiredSignal <= 0) return@LaunchedEffect
        authRepo.expireSession()
    }

    LaunchedEffect(authState, currentRoute) {
        if (authState is AuthState.Error && currentRoute != null && currentRoute != Routes.LOGIN) {
            nav.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNav(
                    nav = nav,
                    currentRoute = currentRoute,
                    hasAnnouncementUnread = hasAnnouncementUnread,
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        // launchSingleTop 防双重导航：登录成功瞬间 LoginScreen 里
                        // `LaunchedEffect(state.success)` 和 `LaunchedEffect(authState)` 两条路径
                        // 都会 fire 一次 onLoggedIn —— 没这个标志会在回退栈里多塞一个 SCHEDULE。
                        nav.navigate(Routes.SCHEDULE) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.SCHEDULE) {
                ScheduleScreen(
                    onOpenExams = { nav.navigate(Routes.EXAMS) },
                )
            }
            composable(Routes.GRADES) {
                GradesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.EXAMS) {
                ExamsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ANNOUNCEMENT) {
                AnnouncementScreen(
                    onItemClick = { a -> nav.navigate(Routes.announcementDetail(a.id)) },
                )
            }
            composable(Routes.CLASSROOM) {
                ClassroomScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.TRAINING_PLAN) {
                TrainingPlanScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.PEOPLE_SEARCH) {
                PeopleSearchScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPerson = { result ->
                        when (result) {
                            is PersonResult.TeacherResult -> nav.navigate(
                                Routes.teacherDetail(result.userNum, result.name),
                            )
                            is PersonResult.StudentResult -> nav.navigate(
                                Routes.studentDetail(result.userNum, result.name, result.department),
                            )
                        }
                    },
                )
            }
            composable(
                route = Routes.TEACHER_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                TeacherDetailScreen(
                    userNum = userNum,
                    name = name,
                    onBack = { nav.popBackStack() },
                    onOpenSchedule = { nav.navigate(Routes.teacherSchedule(userNum, name)) },
                )
            }
            composable(
                route = Routes.TEACHER_SCHEDULE,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                UserScheduleScreen(
                    scheduleUrl = cn.jxnu.nvzhuanban.data.network.JxnuUrls.teacherScheduleUrl(userNum),
                    name = name,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.STUDENT_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("department") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                val department = backStackEntry.arguments?.getString("department").orEmpty()
                    .takeUnless { it == "-" }.orEmpty()
                StudentDetailScreen(
                    userNum = userNum,
                    name = name,
                    department = department,
                    onBack = { nav.popBackStack() },
                    onOpenSchedule = { nav.navigate(Routes.studentSchedule(userNum, name)) },
                )
            }
            composable(
                route = Routes.STUDENT_SCHEDULE,
                arguments = listOf(
                    androidx.navigation.navArgument("userNum") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") { type = androidx.navigation.NavType.StringType },
                ),
            ) { backStackEntry ->
                val userNum = backStackEntry.arguments?.getString("userNum").orEmpty()
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                UserScheduleScreen(
                    scheduleUrl = cn.jxnu.nvzhuanban.data.network.JxnuUrls.studentScheduleUrl(userNum),
                    name = name,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onLogout = {
                        nav.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenGrades = { nav.navigate(Routes.GRADES) },
                    onOpenClassroom = { nav.navigate(Routes.CLASSROOM) },
                    onOpenExams = { nav.navigate(Routes.EXAMS) },
                    onOpenTrainingPlan = { nav.navigate(Routes.TRAINING_PLAN) },
                    onOpenPeopleSearch = { nav.navigate(Routes.PEOPLE_SEARCH) },
                    onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                )
            }
            composable(
                route = Routes.ANNOUNCEMENT_DETAIL,
                arguments = listOf(androidx.navigation.navArgument("articleId") {
                    type = androidx.navigation.NavType.StringType
                }),
            ) { backStackEntry ->
                val articleId = backStackEntry.arguments?.getString("articleId").orEmpty()
                AnnouncementDetailScreen(
                    articleId = articleId,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun BottomNav(
    nav: NavHostController,
    currentRoute: String?,
    hasAnnouncementUnread: Boolean,
) {
    NavigationBar {
        TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            val showDot = tab.route == Routes.ANNOUNCEMENT && hasAnnouncementUnread
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        nav.navigate(tab.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    val icon: @Composable () -> Unit = {
                        Icon(
                            imageVector = if (selected) tab.filled else tab.outlined,
                            contentDescription = null,
                        )
                    }
                    if (showDot) {
                        BadgedBox(badge = { Badge() }) { icon() }
                    } else {
                        icon()
                    }
                },
                label = { Text(stringResource(tab.labelRes)) },
                alwaysShowLabel = false,
            )
        }
    }
}
