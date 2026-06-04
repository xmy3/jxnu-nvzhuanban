package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.model.Exam
import cn.jxnu.nvzhuanban.data.model.ExamStatus
import cn.jxnu.nvzhuanban.data.model.SectionTimetable
import cn.jxnu.nvzhuanban.data.model.formatCredit
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.repository.ExamRepository
import cn.jxnu.nvzhuanban.data.storage.ScheduleHeightPrefs
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

private val LEFT_LABEL_WIDTH = 36.dp
private val SECTIONS = SectionTimetable.SECTION_COUNT
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 离线提示条：当 [ScheduleScreenState.isOffline] 为真（本次拉取失败、展示的是磁盘缓存）时，
 * 在表头下方显示一条说明，告诉用户这是上次的课表、下拉可重试，避免误以为是实时数据。
 */
@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "无网络 · 显示上次缓存的课表，下拉可重试",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenExams: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    var editingWeeksFor by remember { mutableStateOf<Course?>(null) }
    var showSemesterSheet by remember { mutableStateOf(false) }

    // 课程详情 / 学期选择 / 周次编辑 sheet 开着时拦截系统返回键 → 先关 sheet 而不是退出 App。
    // ModalBottomSheet 自身在新版会响应返回键调 onDismissRequest，这里显式拦截做兜底
    BackHandler(enabled = editingWeeksFor != null) {
        editingWeeksFor = null
    }
    BackHandler(enabled = selectedCourse != null && editingWeeksFor == null) {
        selectedCourse = null
    }
    BackHandler(enabled = showSemesterSheet) {
        showSemesterSheet = false
    }

    // 仅当用户当前选中的是"本周"时，今天列才高亮。否则切到别的周看课表时不该有"今天"概念。
    val todayWeekday: Int = remember(state.selectedWeek, state.selectedSemesterValue) {
        if (viewModel.isCurrentWeek(state.selectedWeek)) LocalDate.now().dayOfWeek.value else -1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    SemesterTitle(
                        weekText = stringResource(R.string.schedule_week_template, state.selectedWeek),
                        semester = state.semester,
                        clickable = state.semesters.size > 1,
                        onClick = { showSemesterSheet = true },
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.jumpToToday() }) {
                        Icon(Icons.Outlined.Today, contentDescription = stringResource(R.string.schedule_today))
                    }
                    RefreshIconButton(isRefreshing = isRefreshing, onClick = viewModel::refresh)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
            WeekChips(
                total = state.totalWeeks,
                selected = state.selectedWeek,
                isCurrentWeek = viewModel::isCurrentWeek,
                onSelect = viewModel::selectWeek,
            )
            UpcomingExamBanner(onClick = onOpenExams)
            WeekdayHeader(
                todayWeekday = todayWeekday,
                semesterStart = state.semesterStart,
                selectedWeek = state.selectedWeek,
            )
            if (state.isOffline) OfflineBanner()
            StateScaffold(
                state = state.data,
                onRetry = viewModel::refresh,
            ) { courses ->
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                ) {
                    // 课程的 weeks 字段：教务网原始数据都是 1..18，用户改过的课用 CourseOverridesStore
                    // 的覆盖（仓库层已经替换好了）。这里按选中的周次筛掉不上的课。
                    // remember 缓存：courses 大小常态 20-40，但切周次按钮、拖动手势都会触发上游重组，
                    // 不缓存的话每次都要走一遍 List.filter，下游 ScheduleGrid 还会再 groupBy 一次。
                    val visibleCourses = remember(courses, state.selectedWeek) {
                        courses.filter { it.isInWeek(state.selectedWeek) }
                    }
                    val onSwipeLeft = {
                        if (state.selectedWeek < state.totalWeeks) {
                            viewModel.selectWeek(state.selectedWeek + 1)
                        }
                    }
                    val onSwipeRight = {
                        if (state.selectedWeek > 1) {
                            viewModel.selectWeek(state.selectedWeek - 1)
                        }
                    }
                    if (visibleCourses.isEmpty()) {
                        EmptyWeek(onSwipeLeft = onSwipeLeft, onSwipeRight = onSwipeRight)
                    } else {
                        ScheduleGrid(
                            courses = visibleCourses,
                            todayWeekday = todayWeekday,
                            onCourseClick = { selectedCourse = it },
                            onSwipeLeft = onSwipeLeft,
                            onSwipeRight = onSwipeRight,
                        )
                    }
                }
            }
        }
    }

    if (selectedCourse != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedCourse = null },
            sheetState = sheetState,
        ) {
            CourseDetailSheet(
                course = selectedCourse!!,
                weekTotal = state.totalWeeks,
                onEditWeeks = { editingWeeksFor = selectedCourse },
            )
        }
    }

    if (editingWeeksFor != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingWeeksFor = null },
            sheetState = sheetState,
        ) {
            WeekEditorSheet(
                course = editingWeeksFor!!,
                totalWeeks = state.totalWeeks,
                onCancel = { editingWeeksFor = null },
                onSave = { newWeeks ->
                    // null → 恢复默认；非空列表 → 用户自定义；空列表用户主动选了"没有任何周"也算 override
                    viewModel.updateCourseWeeks(editingWeeksFor!!.name, newWeeks)
                    editingWeeksFor = null
                    selectedCourse = null
                },
            )
        }
    }

    if (showSemesterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSemesterSheet = false },
            sheetState = sheetState,
        ) {
            SemesterPickerSheet(
                semesters = state.semesters,
                selectedValue = state.selectedSemesterValue,
                onSelect = { value ->
                    showSemesterSheet = false
                    viewModel.selectSemester(value)
                },
            )
        }
    }
}

@Composable
private fun SemesterTitle(
    weekText: String,
    semester: String,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = if (clickable) {
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        } else {
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        },
    ) {
        Text(
            text = weekText,
            style = MaterialTheme.typography.titleLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = semester,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (clickable) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "切换学期",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SemesterPickerSheet(
    semesters: List<SchedulePage.SemesterOption>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = "选择学期",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // 学期可能有十多个（包含历史 + 未来），用 LazyColumn 避免一次性 measure 全部行造成卡顿，
        // 且能在 ModalBottomSheet 内部正确滚动到选中的学期附近
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(semesters, key = { it.value }) { option ->
                val isSelected = option.value == selectedValue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.value) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.isCurrent) {
                            Text(
                                text = "本学期",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Spacer(height = 8.dp)
    }
}

@Composable
private fun WeekChips(
    total: Int,
    selected: Int,
    isCurrentWeek: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        // 居中显示当前选中：滚动到 (selected-1)-2，让左侧也露 2 个上下文周。
        // 旧实现用 animateScrollToItem(selected-1)，目标 chip 会贴在视口最左侧。
        val target = (selected - 3).coerceAtLeast(0)
        listState.animateScrollToItem(target)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..total).toList()) { week ->
            FilterChip(
                selected = week == selected,
                onClick = { onSelect(week) },
                label = {
                    Text(
                        text = if (isCurrentWeek(week)) "第 $week 周·本周" else "第 $week 周",
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun WeekdayHeader(
    todayWeekday: Int,
    semesterStart: LocalDate?,
    selectedWeek: Int,
) {
    // 算出当前 week 的周一日期：先把 semesterStart 对齐到它所在那周的周一（previousOrSame），
    // 再加 (week-1)*7 天。这样 currentWeekAt() 算出来的 baselineWeek 跟 UI 显示的日期是同一套坐标。
    val weekMonday: LocalDate? = remember(semesterStart, selectedWeek) {
        semesterStart
            ?.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            ?.plusDays(((selectedWeek - 1) * 7).toLong())
    }
    val dateFormatter = remember { java.time.format.DateTimeFormatter.ofPattern("M/d") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(width = LEFT_LABEL_WIDTH)
        WEEKDAY_LABELS.forEachIndexed { idx, label ->
            val weekday = idx + 1
            val isToday = weekday == todayWeekday
            val date = weekMonday?.plusDays((weekday - 1).toLong())
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isToday) "$label·今" else label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (date != null) {
                    Text(
                        text = date.format(dateFormatter),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleGrid(
    courses: List<Course>,
    todayWeekday: Int,
    onCourseClick: (Course) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    // 阈值用 px：滑动距离超过 ~80dp 才认为是切周，避免误触
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // pointerInput 的 key 是 Unit（手势协程不重启），而 onSwipeLeft/Right 闭包了 selectedWeek 快照。
    // 用 rememberUpdatedState 让手势回调始终读到最新一帧的 lambda，否则切周后横滑会按进入时的旧周次切。
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)

    // 格高：prefs 是真值，手势期间用 liveDp 驱动 UI 实时跟手，松手时一次性落盘。
    // LaunchedEffect 处理"外部把 prefs 改了"的极端路径（目前没有别处会改，但留着便宜）。
    val storedDp by ScheduleHeightPrefs.sectionHeightDp.collectAsState()
    val liveDp = remember { mutableFloatStateOf(storedDp) }
    LaunchedEffect(storedDp) { liveDp.floatValue = storedDp }
    val sectionHeight: Dp = liveDp.floatValue.dp

    // 按 weekday 预分组：每次重组前 DayColumn 都要 7 次 filter（每天一次）。courses 改动频率远低于重组频率
    // （拖动 / hover / 高亮分钟级 tick 都会触发重组），用 remember 缓存能省掉绝大多数 O(N*7) 扫描。
    val coursesByDay = remember(courses) { courses.groupBy { it.weekday } }

    // 当前在本周时，每分钟轮询一次"下节/上课中"课程做高亮。
    // todayWeekday<=0 表示不是本周，此时不计算（state 始终 null，无开销）
    var highlightState by remember(courses, todayWeekday) { mutableStateOf<HighlightedCourse?>(null) }
    LaunchedEffect(courses, todayWeekday) {
        if (todayWeekday <= 0) {
            highlightState = null
            return@LaunchedEffect
        }
        while (true) {
            highlightState = computeHighlight(
                coursesToday = coursesByDay[todayWeekday].orEmpty(),
                now = LocalTime.now(),
            )
            delay(60_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 自管理的手势循环：开头允许"无第二指 → 横向切周"或"出现第二指 → 切到捏合"，
                // 二选一，避免两个 pointerInput 并存时 detectHorizontalDragGestures 已经吃掉首指针造成误切周。
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startedDp = liveDp.floatValue
                    var sawSecondPointer = false
                    var horizontalAccum = 0f
                    var horizontalConsumed = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (!sawSecondPointer && pressedCount >= 2) sawSecondPointer = true

                        if (sawSecondPointer) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val next = (liveDp.floatValue * zoom)
                                    .coerceIn(ScheduleHeightPrefs.MIN_DP, ScheduleHeightPrefs.MAX_DP)
                                // 边界饱和后不写：否则手指继续张开/合拢后再反向时要"先空走"一段
                                if (next != liveDp.floatValue) {
                                    liveDp.floatValue = next
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } else {
                            // 单指水平累计，复刻原 detectHorizontalDragGestures 行为
                            val change = event.changes.firstOrNull { it.id == first.id }
                                ?: event.changes.first()
                            val dx = change.positionChange().x
                            if (dx != 0f) {
                                horizontalAccum += dx
                                if (!horizontalConsumed && abs(horizontalAccum) > touchSlop) {
                                    horizontalConsumed = true
                                }
                                if (horizontalConsumed) change.consume()
                            }
                        }

                        if (event.changes.all { !it.pressed }) break
                    }

                    if (sawSecondPointer) {
                        // 全松开 commit：仅当确实变化过才写 prefs
                        if (liveDp.floatValue != startedDp) {
                            ScheduleHeightPrefs.setSectionHeightDp(liveDp.floatValue)
                        }
                    } else {
                        when {
                            horizontalAccum <= -swipeThresholdPx -> currentSwipeLeft()
                            horizontalAccum >= swipeThresholdPx -> currentSwipeRight()
                        }
                    }
                }
            }
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            SectionLabels(sectionHeight = sectionHeight)
            // 7 个 weekday 列
            for (day in 1..7) {
                DayColumn(
                    courses = coursesByDay[day].orEmpty(),
                    isToday = day == todayWeekday,
                    highlight = if (day == todayWeekday) highlightState else null,
                    onCourseClick = onCourseClick,
                    sectionHeight = sectionHeight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SectionLabels(sectionHeight: Dp) {
    Column(modifier = Modifier.width(LEFT_LABEL_WIDTH)) {
        for (i in 1..SECTIONS) {
            Box(
                modifier = Modifier
                    .height(sectionHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = i.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = SectionTimetable.startTimeLabel(i),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    courses: List<Course>,
    isToday: Boolean,
    highlight: HighlightedCourse?,
    onCourseClick: (Course) -> Unit,
    sectionHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(sectionHeight * SECTIONS)
            .background(
                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                else Color.Transparent,
            )
            .padding(horizontal = 2.dp),
    ) {
        // 背景节次分隔线
        Column(modifier = Modifier.fillMaxSize()) {
            for (i in 0 until SECTIONS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sectionHeight)
                        .background(
                            if (i == 4 || i == 6 || i == 9) {
                                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
                            } else Color.Transparent,
                        ),
                )
            }
        }
        // 课程卡片层
        courses.forEach { course ->
            CourseCard(
                course = course,
                highlightTag = highlight?.takeIf { it.courseId == course.id }?.tag,
                sectionHeight = sectionHeight,
                onClick = { onCourseClick(course) },
            )
        }
    }
}

@Composable
private fun CourseCard(
    course: Course,
    highlightTag: String?,
    sectionHeight: Dp,
    onClick: () -> Unit,
) {
    val yOffsetDp = sectionHeight * (course.startSection - 1)
    val heightDp = sectionHeight * course.sectionCount
    val locationMaxLines = when {
        course.sectionCount >= 3 -> 3
        course.sectionCount >= 2 -> 2
        else -> 1
    }
    val teacherMaxLines = if (course.sectionCount >= 2) 1 else 0

    Box(
        modifier = Modifier
            .offset(y = yOffsetDp)
            .height(heightDp)
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(courseColor(course))
            .let { base ->
                if (highlightTag != null) {
                    base.border(2.dp, Color.White, RoundedCornerShape(10.dp))
                } else base
            }
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Column {
            // 标签塞进顶行而不是 align(TopEnd) 叠在课程名上：课表列只有 ~50dp 宽，
            // 3 字标签会把课程名整行盖掉
            if (highlightTag != null) {
                Text(
                    text = highlightTag,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    fontWeight = FontWeight.Bold,
                    color = courseColor(course),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                        .padding(horizontal = 4.dp),
                )
                Spacer(height = 2.dp)
            }
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = when {
                    highlightTag != null -> 1
                    course.sectionCount >= 3 -> 3
                    else -> 2
                },
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(height = 2.dp)
            Text(
                text = "@${course.location}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = locationMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (teacherMaxLines > 0 && course.teacher.isNotBlank()) {
                Text(
                    text = course.teacher,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = teacherMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// 通过课程名称的稳定 hash 派生颜色，让同名课程颜色一致
private fun courseColor(course: Course): Color {
    val palette = listOf(
        Color(0xFFE57373),
        Color(0xFFBA68C8),
        Color(0xFF7986CB),
        Color(0xFF4FC3F7),
        Color(0xFF4DB6AC),
        Color(0xFF81C784),
        Color(0xFFFFB74D),
        Color(0xFFA1887F),
        Color(0xFF90A4AE),
        Color(0xFFF06292),
        Color(0xFF9575CD),
        Color(0xFF64B5F6),
    )
    // 用 and Int.MAX_VALUE 取非负：避免 hashCode == Int.MIN_VALUE 时 `-it` 仍为负 → % 出负 index → 越界
    val idx = (course.name.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}

@Composable
private fun EmptyWeek(
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
) {
    // 阈值与 ScheduleGrid 保持一致：80dp 才触发；避免下拉刷新被误判成切周
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    val dragAccum = remember { FloatArray(1) }
    // 同 ScheduleGrid：pointerInput(Unit) 持有首帧闭包，rememberUpdatedState 读最新切周回调
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentSwipeRight by rememberUpdatedState(onSwipeRight)
    // verticalScroll + 上下 Spacer 撑空间：保证 PullToRefreshBox 在空数据时也能识别下拉手势
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum[0] = 0f },
                    onDragEnd = {
                        when {
                            dragAccum[0] <= -swipeThresholdPx -> currentSwipeLeft()
                            dragAccum[0] >= swipeThresholdPx -> currentSwipeRight()
                        }
                        dragAccum[0] = 0f
                    },
                    onDragCancel = { dragAccum[0] = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragAccum[0] += dragAmount },
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(height = 160.dp)
        Text(
            text = stringResource(R.string.schedule_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(height = 160.dp)
    }
}

@Composable
private fun CourseDetailSheet(course: Course, weekTotal: Int, onEditWeeks: () -> Unit) {    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        // 标题：色块 + 课程名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(courseColor(course)),
            )
            Spacer(width = 12.dp)
            Text(
                text = course.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(height = 20.dp)
        // 详情行
        DetailRow(
            icon = Icons.Outlined.Person,
            label = "教师",
            value = course.teacher.ifBlank { "—" },
        )
        DetailRow(
            icon = Icons.Outlined.Place,
            label = "地点",
            value = course.location.ifBlank { "—" },
        )
        DetailRow(
            icon = Icons.Outlined.AccessTime,
            label = "节次",
            value = "周${weekdayLabel(course.weekday)} · 第 ${course.startSection}-${course.endSection} 节 (${sectionTimeRange(course.startSection, course.endSection)})",
        )
        DetailRow(
            icon = Icons.Outlined.CalendarMonth,
            label = "周次",
            value = formatWeeks(course.weeks, weekTotal),
        )
        // 学分由 ScheduleRepository.enrichWithCredits 异步从成绩页补上；
        // 首次进 App 还没补全 / 历史从未修过这门课时 credit 仍为 0，此时不显示该标签
        if (course.credit > 0f) {
            Spacer(height = 12.dp)
            MetaTag(text = "${course.credit.formatCredit()} 学分")
        }
        Spacer(height = 20.dp)
        // 教务网 HTML 不给周次细节，所有课默认 1..18 周；这里给用户一个本地编辑入口
        OutlinedButton(
            onClick = onEditWeeks,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(width = 8.dp)
            Text("编辑周次")
        }
        Spacer(height = 16.dp)
    }
}

/**
 * 编辑某门课实际上课的周次。教务网原始数据所有课都是 1..18 周，学生实际情况可能只上某些周，
 * 这里给一个本地覆盖入口。保存后 [onSave] 收到的 List：
 * - null → 用户选回了 1..[totalWeeks] 全部，等同于"恢复默认"，调用方应清掉 override
 * - 非空 → 用户的实际选择
 *
 * 0 周（全部取消勾选）保存按钮会被禁用 —— 那是"删掉这门课"的语义，不在本编辑器范围。
 */
@Composable
private fun WeekEditorSheet(
    course: Course,
    totalWeeks: Int,
    onCancel: () -> Unit,
    onSave: (List<Int>?) -> Unit,
) {
    // 选中集合用 mutableStateOf<Set<Int>>，比 mutableStateListOf 更适合"无序集合且要整存整取"的场景
    var selected by remember(course.id, totalWeeks) {
        mutableStateOf(course.weeks.toSet())
    }
    val defaultAll = remember(totalWeeks) { (1..totalWeeks).toSet() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = "编辑周次",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(height = 4.dp)
        Text(
            text = course.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(height = 12.dp)

        // 快捷预设：覆盖最常见的几种模式。点了立即应用到 selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetChip("全选") { selected = defaultAll }
            PresetChip("1-8") { selected = (1..minOf(8, totalWeeks)).toSet() }
            // 慕课：江师大慕课课程固定在第 14-15 周排课
            PresetChip("慕课") {
                selected = (14..15).filter { it in 1..totalWeeks }.toSet()
            }
        }
        Spacer(height = 14.dp)

        // 周次方块网格：6 列固定，行数随 totalWeeks 增长。点击切换
        val columns = 6
        val rows = (totalWeeks + columns - 1) / columns
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowIdx in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (colIdx in 0 until columns) {
                        val week = rowIdx * columns + colIdx + 1
                        if (week <= totalWeeks) {
                            WeekCell(
                                week = week,
                                checked = week in selected,
                                onToggle = {
                                    selected = if (week in selected) selected - week else selected + week
                                },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            // 占位让最后一行也保持等分宽
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(height = 8.dp)
        Text(
            text = "已选 ${selected.size} 周 · ${formatWeeks(selected.sorted(), totalWeeks)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(height = 16.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) { Text("取消") }
            Button(
                onClick = {
                    // 选择回到 1..N → null（清掉本地 override，回到教务网默认）
                    onSave(if (selected == defaultAll) null else selected.sorted())
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
        }
        Spacer(height = 16.dp)
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun WeekCell(
    week: Int,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = week.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(width = 14.dp)
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun MetaTag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun weekdayLabel(weekday: Int): String = when (weekday) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
    5 -> "五"; 6 -> "六"; 7 -> "日"
    else -> "?"
}

private fun sectionTimeRange(startSection: Int, endSection: Int): String =
    "${SectionTimetable.startTimeLabel(startSection)}–${SectionTimetable.startTimeLabel(endSection)}"

/**
 * 把上课周列表压缩成可读字符串：
 *   [1,2,3,4,5,6,7,8] → "1-8 周"
 *   [1,3,5,7,9,11,13,15] → "1-15 周(单)"
 *   [2,4,6,8,10] → "2-10 周(双)"
 *   [1,2,3,7,8,9] → "1-3,7-9 周"
 */
private fun formatWeeks(weeks: List<Int>, totalWeeks: Int): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val first = sorted.first()
    val last = sorted.last()
    val expected = (first..last).toList()
    // 全部连续
    if (sorted == expected) return "$first-$last 周"
    // 全部奇数
    val odds = expected.filter { it % 2 == 1 }
    if (sorted == odds) return "$first-$last 周(单)"
    // 全部偶数
    val evens = expected.filter { it % 2 == 0 }
    if (sorted == evens) return "$first-$last 周(双)"
    // 通用：按连续段聚合
    val segments = mutableListOf<IntRange>()
    var segStart = sorted.first()
    var prev = segStart
    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            segments += segStart..prev
            segStart = w
            prev = w
        }
    }
    segments += segStart..prev
    return segments.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" } + " 周"
}

/**
 * 今日要高亮的课程：上课中 > 下节 > 无。
 *
 * - **上课中**：当前时间介于 startSection 起始 与 endSection 结束 之间
 * - **下节**：今天未开始的课程中开始时间最近的一节
 *
 * 没课 / 今日已结束 都返回 null（不高亮）。
 */
private data class HighlightedCourse(val courseId: String, val tag: String)

private fun computeHighlight(coursesToday: List<Course>, now: LocalTime): HighlightedCourse? {
    if (coursesToday.isEmpty()) return null
    val nowMin = now.hour * 60 + now.minute

    val ongoing = coursesToday.firstOrNull { c ->
        nowMin in SectionTimetable.startMinutes(c.startSection)..SectionTimetable.endMinutes(c.endSection)
    }
    if (ongoing != null) return HighlightedCourse(ongoing.id, "上课中")

    val next = coursesToday
        .filter { SectionTimetable.startMinutes(it.startSection) > nowMin }
        .minByOrNull { SectionTimetable.startMinutes(it.startSection) }
    return next?.let { HighlightedCourse(it.id, "下节") }
}

/**
 * 课表顶部的"即将考试"横幅。仅在 7 天内（含今天）有考试时显示，点击跳考试列表。
 *
 * 数据复用 [ExamRepository] 的内存缓存：第一次进 App 时是 best-effort 一次性拉网络，失败 / 暂未拉到都
 * 不显示横幅（不打扰）；后续切 tab 回来直接命中缓存，0 网络。
 *
 * 设计取舍：故意不在 ScheduleViewModel 里持有考试 state，免得课表 VM 耦合考试领域；
 * 横幅是纯 UI 层一次性副作用，组件销毁时自然 cancel。
 */
@Composable
private fun UpcomingExamBanner(onClick: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        // 跨过 00:00 时倒计时要减 1 天；和 ExamsScreen 同样的 60s 节拍
        while (true) {
            delay(60_000L)
            now = LocalDateTime.now()
        }
    }
    var exams by remember { mutableStateOf<List<Exam>?>(null) }
    LaunchedEffect(Unit) {
        exams = runCatching { ExamRepository.instance.fetchAll() }.getOrNull()
    }
    val nearest = exams?.asSequence()
        ?.filter { it.statusAt(now) != ExamStatus.FINISHED }
        ?.filter { it.daysLeftFrom(now) in 0..7 }
        ?.minByOrNull { it.startTime }
        ?: return
    val daysLeft = nearest.daysLeftFrom(now)
    val countWithin7 = exams.orEmpty().count {
        it.statusAt(now) != ExamStatus.FINISHED && it.daysLeftFrom(now) in 0..7
    }
    val labelLeft = when {
        daysLeft <= 0L -> "今天有 ${nearest.courseName} 考试"
        else -> "距 ${nearest.courseName} 考试还有 $daysLeft 天"
    }
    val labelRight = if (countWithin7 > 1) "近 7 天 $countWithin7 场" else "查看全部"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = if (daysLeft <= 0L) 1f else 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(width = 8.dp)
        Text(
            text = labelLeft,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = labelRight,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        )
    }
}

// 一个最小的 width/height Spacer，避免反复写 Modifier.size
@Composable
private fun Spacer(width: androidx.compose.ui.unit.Dp = 0.dp, height: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(modifier = Modifier.size(width = width, height = height))
}
