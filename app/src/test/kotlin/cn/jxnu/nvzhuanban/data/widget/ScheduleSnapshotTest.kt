package cn.jxnu.nvzhuanban.data.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleSnapshotTest {

    @Test
    fun filtersCoursesByDateWeekAndWeekday() {
        val start = LocalDate.of(2026, 3, 2)
        val snapshot = ScheduleSnapshot(
            semester = "2025-2026-2",
            totalWeeks = 18,
            semesterStartEpochDay = start.toEpochDay(),
            savedWeek = 1,
            allCourses = listOf(
                course("周一第一周", weekday = 1, weeks = listOf(1)),
                course("周一第二周", weekday = 1, weeks = listOf(2)),
                course("周二第一周", weekday = 2, weeks = listOf(1)),
            ),
            updatedAt = 0L,
        )

        assertEquals(listOf("周一第一周"), snapshot.coursesOn(start).map { it.name })
        assertEquals(listOf("周一第二周"), snapshot.coursesOn(start.plusWeeks(1)).map { it.name })
        assertTrue(snapshot.coursesOn(start.plusWeeks(19)).isEmpty())
    }

    @Test
    fun fallsBackToSavedWeekWhenSemesterStartIsUnknown() {
        val snapshot = ScheduleSnapshot(
            semester = "legacy",
            totalWeeks = 0,
            semesterStartEpochDay = -1L,
            savedWeek = 3,
            allCourses = listOf(course("旧快照课程", weekday = 1, weeks = listOf(3))),
            updatedAt = 0L,
        )

        assertEquals(3, snapshot.weekAt(LocalDate.of(2026, 1, 1)))
        assertEquals(listOf("旧快照课程"), snapshot.coursesOn(LocalDate.of(2026, 1, 5)).map { it.name })
    }

    private fun course(name: String, weekday: Int, weeks: List<Int>) = SnapshotCourse(
        name = name,
        location = "N101",
        teacher = "Teacher",
        startSection = 1,
        endSection = 2,
        weekday = weekday,
        weeks = weeks,
    )
}
