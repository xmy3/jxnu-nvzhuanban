package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.ui.components.RefreshIconButton
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

internal val LEFT_LABEL_WIDTH = 36.dp
internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

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

    // 「今天」做成可观察状态：页面驻留跨过 00:00（尤其周日→周一）后，「今」列头、今日列底色
    // 和"上课中/下节"高亮都要跟着走。到点校准一次而不是每分钟轮询。
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay())
            delay(untilMidnight.toMillis() + 1_000L)
            today = LocalDate.now()
        }
    }
    // 仅当用户当前选中的是"本周"时，今天列才高亮。否则切到别的周看课表时不该有"今天"概念。
    // currentWeek 来自 VM state，refresh/loadWeek 推进真实周后这里会自动重算。
    val todayWeekday: Int =
        if (state.currentWeek != null && state.currentWeek == state.selectedWeek) {
            today.dayOfWeek.value
        } else -1

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
                currentWeek = state.currentWeek,
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
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WeekChips(
    total: Int,
    selected: Int,
    currentWeek: Int?,
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
        items(count = total) { idx ->
            val week = idx + 1
            FilterChip(
                selected = week == selected,
                onClick = { onSelect(week) },
                label = {
                    Text(
                        text = if (week == currentWeek) "第 $week 周·本周" else "第 $week 周",
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
        Spacer(Modifier.width(LEFT_LABEL_WIDTH))
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
        Spacer(Modifier.height(160.dp))
        Text(
            text = stringResource(R.string.schedule_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(160.dp))
    }
}
