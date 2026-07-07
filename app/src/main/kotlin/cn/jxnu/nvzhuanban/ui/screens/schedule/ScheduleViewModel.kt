package cn.jxnu.nvzhuanban.ui.screens.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.network.toUserMessage
import cn.jxnu.nvzhuanban.data.network.pages.SchedulePage
import cn.jxnu.nvzhuanban.data.repository.ScheduleRepository
import cn.jxnu.nvzhuanban.data.widget.ScheduleSnapshot
import cn.jxnu.nvzhuanban.data.widget.WidgetSnapshotStore
import cn.jxnu.nvzhuanban.ui.components.UiState
import cn.jxnu.nvzhuanban.ui.widget.TodayScheduleWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ScheduleScreenState(
    val selectedWeek: Int,
    val totalWeeks: Int,
    val semester: String,
    val data: UiState<List<Course>> = UiState.Loading,
    /** 所有可选学期。第一次加载完成前是空列表，UI 隐藏切换入口。 */
    val semesters: List<SchedulePage.SemesterOption> = emptyList(),
    /** 当前选中的学期 [SchedulePage.SemesterOption.value]；null 表示尚未确定。 */
    val selectedSemesterValue: String? = null,
    /**
     * 当前学期开学日的开始日期（来自服务器 option value）。WeekdayHeader 用它把
     * `(week, weekday)` 算成具体日期。null 表示尚未加载或服务器没给。
     */
    val semesterStart: LocalDate? = null,
    /**
     * true = 当前展示的是本地磁盘缓存（上次成功看到的课表），因为这次拉取网络失败。
     * UI 顶部据此显示"离线·上次课表"提示；下次成功拉取后复位 false。
     */
    val isOffline: Boolean = false,
    /**
     * 本学期由开学日期算出的"当前教学周"；查看历史/未来学期时为 null（无"本周/今天"概念）。
     * 放在 state 里让 UI 可观察 —— refresh/loadWeek 推进它时列头「今」与周 chip「本周」要跟着走。
     */
    val currentWeek: Int? = null,
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    // 不放进主构造器：Kotlin 默认参数不会生成 `(Application)` 重载，
    // SavedStateViewModelFactory 会因找不到 AndroidViewModel 标准构造器而抛错
    private val repo: ScheduleRepository = ScheduleRepository.instance

    /**
     * 当前教学周。初值是 repo 缓存里的值（首次启动为 1），课表拉取完成后会被覆盖为
     * 通过学期开学日期计算出的真实周。
     */
    private var baselineWeek: Int = repo.currentWeek()
    private var hasLoadedOnce: Boolean = false

    /** 用户是否手动切过学期。切过就别让"今日"按钮反复把他们拉回本学期。 */
    private var userPickedSemester: Boolean = false

    private val _state = MutableStateFlow(
        ScheduleScreenState(
            selectedWeek = baselineWeek,
            totalWeeks = repo.totalWeeks(),
            semester = repo.currentSemester(),
            semesters = repo.availableSemesters(),
            selectedSemesterValue = repo.currentSemesterValue(),
            semesterStart = repo.currentSemesterStart(),
            currentWeek = baselineWeek,
        ),
    )
    val state: StateFlow<ScheduleScreenState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { loadWeek(baselineWeek) }

    /**
     * 切换显示的教学周。仅更新 [_state]，**不**重新拉教务网 —— 江师大课表 HTML 不区分周次，
     * 同一份 list 在所有周都返回相同数据，UI 层会用 [Course.isInWeek] 重新过滤。
     * 把 loadWeek 也跑一遍会把 data 翻成 Loading，闪一下骨架屏，没必要。
     */
    fun selectWeek(week: Int) {
        val bounded = week.coerceIn(1, _state.value.totalWeeks)
        if (bounded == _state.value.selectedWeek) return
        _state.update { it.copy(selectedWeek = bounded) }
    }

    /**
     * 点 "今天" 按钮 → 总是回到「本学期 + 本周」。
     * 当前已在本学期就只切周；不在本学期就先切学期、内部 selectSemester 会把周设到 baselineWeek。
     *
     * 语义就是"撤回任何主动选择"，所以两条分支都把 [userPickedSemester] 复位。
     * selectSemester 内部会把它再翻 true，但这里 launch 内复位发生在 selectSemester 完成之后，
     * 不会影响这次切换内的 selectedWeek 计算，只影响下一次 loadWeek 的 auto-snap 判断。
     */
    fun jumpToToday() {
        userPickedSemester = false
        val current = repo.availableSemesters().firstOrNull { it.isCurrent }
        if (current != null && current.value != _state.value.selectedSemesterValue) {
            selectSemester(current.value)
            // selectSemester launch 一个 coroutine 并已经 set userPickedSemester=true（同步部分），
            // 我们要的语义是"撤回主动选择"——让 selectSemester 完成后再次复位。
            viewModelScope.launch { userPickedSemester = false }
        } else {
            selectWeek(baselineWeek)
        }
    }

    /**
     * 切学期。POST 教务网 → 缓存解析结果 → 重置 selectedWeek / totalWeeks / semester。
     * 同 value 重复切换会被 Repository 短路；已缓存的学期秒切。
     */
    fun selectSemester(value: String) {
        if (value == _state.value.selectedSemesterValue) return
        userPickedSemester = true
        // 保留旧课表数据，不切 Loading：已缓存学期 Repository 会秒切（不闪 spinner），
        // 未缓存的场景用户看到旧数据停留 200ms 也比白屏好。失败路径里会显式切 Error。
        viewModelScope.launch {
            try {
                repo.selectSemester(value)
                val list = repo.getSchedule(_state.value.selectedWeek)
                // 切到当前学期才有意义的 baselineWeek
                val isCurrentSemester = repo.availableSemesters()
                    .firstOrNull { it.value == value }?.isCurrent == true
                if (isCurrentSemester) baselineWeek = repo.currentWeek()
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = repo.totalWeeks(),
                        selectedSemesterValue = value,
                        semesters = repo.availableSemesters(),
                        semesterStart = repo.currentSemesterStart(),
                        // 切到本学期 → 跳到本周；切到历史/未来学期 → 第 1 周
                        selectedWeek = if (isCurrentSemester) baselineWeek else 1,
                        isOffline = false,
                        currentWeek = if (isCurrentSemester) baselineWeek else null,
                    )
                }
                refreshWidgetSnapshot(list)
            } catch (t: Throwable) {
                _state.update { it.copy(data = UiState.Error(t.toUserMessage("切换学期失败"))) }
            }
        }
    }

    /**
     * 下拉刷新或手动刷新。不切换 data 到 Loading，保留旧数据避免列表闪烁。
     * 顶部 spinner 由 [isRefreshing] 驱动。
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                repo.refresh()
                // 教务网偶尔会改 totalWeeks（个别学期 16/20 周），把当前选中的周次约束回新范围
                val newTotal = repo.totalWeeks()
                val coercedWeek = _state.value.selectedWeek.coerceIn(1, newTotal.coerceAtLeast(1))
                if (coercedWeek != _state.value.selectedWeek) {
                    _state.update { it.copy(selectedWeek = coercedWeek) }
                }
                val list = repo.getSchedule(coercedWeek)
                baselineWeek = repo.currentWeek()
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = newTotal,
                        semesters = repo.availableSemesters(),
                        selectedSemesterValue = repo.currentSemesterValue(),
                        semesterStart = repo.currentSemesterStart(),
                        isOffline = false,
                        currentWeek = currentWeekOrNull(),
                    )
                }
                refreshWidgetSnapshot(list)
                // 若上次未成功补学分，这里再试一次（已成功则 enrichWithCredits 内部短路）
                tryEnrichCredits()
            } catch (t: Throwable) {
                // data 已是 Success（在线旧数据或离线缓存）→ 保留不动；仅当还停在 Loading 才兜底/报错
                if (_state.value.data is UiState.Loading) {
                    fallbackToSnapshotOrError(t)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** [ScheduleScreenState.currentWeek] 的取值：仅当选中的是"本学期"时才有"本周"概念。 */
    private fun currentWeekOrNull(): Int? {
        val selectedValue = repo.currentSemesterValue()
        val isCurrentSemester = selectedValue == null ||
            repo.availableSemesters().firstOrNull { it.value == selectedValue }?.isCurrent == true
        return if (isCurrentSemester) baselineWeek else null
    }

    /**
     * 修改某门课实际上课的周次。[weeks] 为 null/空 = 恢复教务网默认（1..18）。
     * 不重新拉教务网，直接重新算当前 selectedWeek 的课列表即可——仓库层会把 override 应用上。
     */
    fun updateCourseWeeks(courseName: String, weeks: List<Int>?) {
        repo.setCourseWeeks(courseName, weeks)
        viewModelScope.launch {
            val list = runCatching { repo.getSchedule(_state.value.selectedWeek) }.getOrNull()
                ?: return@launch
            _state.update {
                if (it.data is UiState.Success) it.copy(data = UiState.Success(list)) else it
            }
            refreshWidgetSnapshot(list)
        }
    }

    private fun loadWeek(week: Int) {
        _state.update { it.copy(data = UiState.Loading) }
        viewModelScope.launch {
            try {
                val list = repo.getSchedule(week)
                val wasFirstLoad = !hasLoadedOnce
                hasLoadedOnce = true
                baselineWeek = repo.currentWeek()
                _state.update {
                    it.copy(
                        data = UiState.Success(list),
                        semester = repo.currentSemester(),
                        totalWeeks = repo.totalWeeks(),
                        semesters = repo.availableSemesters(),
                        selectedSemesterValue = repo.currentSemesterValue(),
                        semesterStart = repo.currentSemesterStart(),
                        // 仅首次加载、且用户没主动切过学期时，把 selectedWeek 拉到当前周
                        selectedWeek = if (wasFirstLoad && !userPickedSemester) baselineWeek else it.selectedWeek,
                        isOffline = false,
                        currentWeek = currentWeekOrNull(),
                    )
                }
                // 每次成功拿到课表，把"今天 + 当前周"的快照存给桌面小部件用
                refreshWidgetSnapshot(list)
                // 首次加载完成后，后台拉一次成绩页给课程详情补上学分（详见 ScheduleRepository.enrichWithCredits）
                if (wasFirstLoad) tryEnrichCredits()
            } catch (t: Throwable) {
                // 网络失败 → 回退磁盘快照（上次成功看到的本学期课表），离线也能看
                fallbackToSnapshotOrError(t)
            }
        }
    }

    /**
     * Best-effort：异步从成绩页拉学分映射，成功后用补全学分的 list 再 emit 一次。
     * 失败静默 —— 课表本身已经能展示，学分缺失只是细节回退到 0。
     */
    private fun tryEnrichCredits() {
        viewModelScope.launch {
            val enriched = runCatching { repo.enrichWithCredits() }.getOrDefault(false)
            if (!enriched) return@launch
            // 重新查一次同一周的列表，此时 ScheduleRepository 会把 0 学分自动补上
            val refreshed = runCatching { repo.getSchedule(_state.value.selectedWeek) }.getOrNull()
                ?: return@launch
            _state.update {
                if (it.data is UiState.Success) it.copy(data = UiState.Success(refreshed)) else it
            }
        }
    }

    /**
     * 把当前学期全部课程写入 [WidgetSnapshotStore]，并通知 [TodayScheduleWidget] 立即刷新。
     * Widget 进程独立运行，不主动联网，所有数据靠这一份快照；它会在渲染时按"此刻"
     * 现场算 weekday / week / 今日课程，因此 App 几天没开也能正确切日 / 切节次。
     *
     * 历史学期不要写 snapshot —— widget 永远显示"本学期·今天"。
     * 整个写入流程跑在 IO：JSON 序列化 + 文件写 + AppWidgetManager binder 调用都不能在 Main。
     */
    /** 离线兜底用的还原结果（从磁盘快照转出来）。 */
    private data class RestoredSchedule(
        val courses: List<Course>,
        val semester: String,
        val totalWeeks: Int,
        val semesterStart: LocalDate?,
        val week: Int,
    )

    /** 读磁盘快照（上次成功保存的本学期课表）并还原成可展示数据；无快照返回 null。 */
    private suspend fun loadOfflineSnapshot(): RestoredSchedule? {
        val ctx = getApplication<Application>()
        val snap = withContext(Dispatchers.IO) { WidgetSnapshotStore.load(ctx) }
        if (snap.allCourses.isEmpty()) return null
        return RestoredSchedule(
            courses = snap.toCourses(),
            semester = snap.semester.ifBlank { "上次课表" },
            totalWeeks = if (snap.totalWeeks > 0) snap.totalWeeks else 18,
            semesterStart = if (snap.hasSemesterStart) LocalDate.ofEpochDay(snap.semesterStartEpochDay) else null,
            week = snap.weekAt().coerceAtLeast(1),
        )
    }

    /**
     * 拉取失败时的统一兜底：有磁盘快照就以「离线态」展示上次看到的课表，否则升级为 Error。
     * 离线态隐藏学期切换（快照不含可选学期列表），并把周次/学期重置成快照里的值。
     */
    private suspend fun fallbackToSnapshotOrError(error: Throwable) {
        val offline = loadOfflineSnapshot()
        if (offline == null) {
            _state.update { it.copy(data = UiState.Error(error.toUserMessage()), isOffline = false) }
            return
        }
        hasLoadedOnce = true
        baselineWeek = offline.week
        _state.update {
            it.copy(
                data = UiState.Success(offline.courses),
                semester = offline.semester,
                totalWeeks = offline.totalWeeks,
                semesterStart = offline.semesterStart,
                selectedSemesterValue = null,
                semesters = emptyList(),
                selectedWeek = offline.week.coerceIn(1, offline.totalWeeks.coerceAtLeast(1)),
                isOffline = true,
                // 快照只存"本学期"的课表，还原出的周就是当前周
                currentWeek = offline.week,
            )
        }
    }

    private fun refreshWidgetSnapshot(all: List<Course>) {
        // 切到历史/未来学期时不更新 widget snapshot —— widget 永远显示"本学期·今天"
        val isCurrentSemester = repo.availableSemesters()
            .firstOrNull { it.value == repo.currentSemesterValue() }?.isCurrent == true
        if (!isCurrentSemester) return
        val ctx = getApplication<Application>()
        val snap = ScheduleSnapshot.fromCourses(
            semester = repo.currentSemester(),
            totalWeeks = repo.totalWeeks(),
            semesterStart = repo.currentSemesterStart(),
            all = all,
        )
        val snapshotGeneration = WidgetSnapshotStore.generation()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            WidgetSnapshotStore.save(ctx, snap, snapshotGeneration)
            runCatching {
                // 只通知已经被用户添加到桌面的 widget，避免没有 widget 时报错
                val mgr = GlanceAppWidgetManager(ctx)
                if (mgr.getGlanceIds(TodayScheduleWidget::class.java).isNotEmpty()) {
                    TodayScheduleWidget().updateAll(ctx)
                }
            }
        }
    }
}
