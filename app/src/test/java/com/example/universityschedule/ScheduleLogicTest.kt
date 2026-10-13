package com.example.universityschedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class ScheduleLogicTest {

    private fun lesson(start: String, end: String, subject: String = "$start-$end", cancelled: Boolean = false) =
        UniversityClass(
            id = 0,
            startTime = start,
            endTime = end,
            subject = subject,
            teacher = "",
            room = "",
            dayOfWeek = Calendar.MONDAY,
            cancelled = cancelled
        )

    private fun at(time: String) = ScheduleLogic.toMinutes(time)!!

    @Test
    fun toMinutes_parsesWithAndWithoutLeadingZero() {
        assertEquals(545, ScheduleLogic.toMinutes("09:05"))
        assertEquals(545, ScheduleLogic.toMinutes("9:05"))
        assertEquals(0, ScheduleLogic.toMinutes("00:00"))
        assertEquals(1439, ScheduleLogic.toMinutes("23:59"))
    }

    @Test
    fun toMinutes_rejectsGarbage() {
        assertNull(ScheduleLogic.toMinutes(""))
        assertNull(ScheduleLogic.toMinutes("9"))
        assertNull(ScheduleLogic.toMinutes("24:00"))
        assertNull(ScheduleLogic.toMinutes("10:60"))
        assertNull(ScheduleLogic.toMinutes("ab:cd"))
    }

    @Test
    fun sortedByStart_comparesTimesNumerically() {
        val sorted = ScheduleLogic.sortedByStart(listOf(lesson("10:00", "11:30"), lesson("9:00", "9:45")))
        assertEquals(listOf("9:00", "10:00"), sorted.map { it.startTime })
    }

    @Test
    fun daysFromMonday_mapsWholeWeek() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), ScheduleLogic.WEEK_DAYS.map { ScheduleLogic.daysFromMonday(it) })
    }

    @Test
    fun timing_endIsExclusive() {
        val l = lesson("09:00", "10:30")
        assertEquals(ClassTiming.FUTURE, ScheduleLogic.timing(l, at("08:59")))
        assertEquals(ClassTiming.CURRENT, ScheduleLogic.timing(l, at("09:00")))
        assertEquals(ClassTiming.CURRENT, ScheduleLogic.timing(l, at("10:29")))
        assertEquals(ClassTiming.PAST, ScheduleLogic.timing(l, at("10:30")))
    }

    @Test
    fun todayStatus_coversWholeDay() {
        // Пары записаны не по порядку и без ведущего нуля — так бывает в Firestore
        val day = listOf(lesson("10:40", "12:10", "second"), lesson("9:00", "10:30", "first"))

        assertEquals(DayStatus.BeforeFirst(day[1]), ScheduleLogic.todayStatus(day, at("08:00")))
        assertEquals(DayStatus.InProgress(day[1], 30), ScheduleLogic.todayStatus(day, at("10:00")))
        assertEquals(DayStatus.Break(day[0]), ScheduleLogic.todayStatus(day, at("10:35")))
        assertEquals(DayStatus.InProgress(day[0], 1), ScheduleLogic.todayStatus(day, at("12:09")))
        assertEquals(DayStatus.Finished, ScheduleLogic.todayStatus(day, at("12:10")))
    }

    @Test
    fun todayStatus_ignoresCancelledClasses() {
        val cancelledFirst = lesson("09:00", "10:30", "cancelled", cancelled = true)
        val second = lesson("10:40", "12:10", "second")

        assertEquals(DayStatus.BeforeFirst(second), ScheduleLogic.todayStatus(listOf(cancelledFirst, second), at("09:30")))
        assertEquals(DayStatus.NoClasses, ScheduleLogic.todayStatus(listOf(cancelledFirst), at("09:30")))
        assertEquals(DayStatus.NoClasses, ScheduleLogic.todayStatus(emptyList(), at("09:30")))
    }
}
