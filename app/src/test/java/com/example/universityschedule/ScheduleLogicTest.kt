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

    // Понедельник, 12 октября 2026, в часовом поясе Москвы
    private fun monday(time: String): Calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Moscow")).apply {
        clear()
        set(2026, Calendar.OCTOBER, 12)
        val minutes = at(time)
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }

    private fun lessonOn(day: Int, start: String, subject: String, cancelled: Boolean = false) =
        lesson(start, "23:00", subject, cancelled).copy(dayOfWeek = day)

    @Test
    fun nextReminder_picksNextClassToday() {
        val first = lessonOn(Calendar.MONDAY, "9:00", "first")
        val second = lessonOn(Calendar.MONDAY, "10:40", "second")
        val schedule = mapOf(Calendar.MONDAY to listOf(second, first))

        val reminder = ScheduleLogic.nextReminder(schedule, monday("08:00"), 15)!!
        assertEquals(first, reminder.universityClass)
        assertEquals(monday("08:45").timeInMillis, reminder.triggerAtMillis)
        assertEquals(monday("09:00").timeInMillis, reminder.startAtMillis)

        // Время напоминания о первой паре уже прошло — берём вторую
        assertEquals(second, ScheduleLogic.nextReminder(schedule, monday("08:50"), 15)!!.universityClass)
    }

    @Test
    fun nextReminder_skipsCancelledAndWrapsToNextWeek() {
        val cancelled = lessonOn(Calendar.WEDNESDAY, "9:00", "cancelled", cancelled = true)
        val mondayClass = lessonOn(Calendar.MONDAY, "9:00", "monday")
        val schedule = mapOf(Calendar.MONDAY to listOf(mondayClass), Calendar.WEDNESDAY to listOf(cancelled))

        // Пары понедельника уже прошли, среда отменена — следующее напоминание через неделю
        val reminder = ScheduleLogic.nextReminder(schedule, monday("12:00"), 10)!!
        assertEquals(mondayClass, reminder.universityClass)
        val nextMonday = monday("08:50").apply { add(Calendar.DAY_OF_MONTH, 7) }
        assertEquals(nextMonday.timeInMillis, reminder.triggerAtMillis)
    }

    @Test
    fun nextReminder_offOrEmpty() {
        val schedule = mapOf(Calendar.MONDAY to listOf(lessonOn(Calendar.MONDAY, "9:00", "first")))
        assertNull(ScheduleLogic.nextReminder(schedule, monday("08:00"), 0))
        assertNull(ScheduleLogic.nextReminder(emptyMap(), monday("08:00"), 15))
    }

    @Test
    fun widgetState_showsCurrentNextOrNothing() {
        val first = lessonOn(Calendar.MONDAY, "9:00", "first").copy(endTime = "10:30")
        val second = lessonOn(Calendar.MONDAY, "10:40", "second").copy(endTime = "12:10")
        val tuesday = lessonOn(Calendar.TUESDAY, "9:00", "tuesday").copy(endTime = "10:30")
        val schedule = mapOf(Calendar.MONDAY to listOf(first, second), Calendar.TUESDAY to listOf(tuesday))

        assertEquals(WidgetState.Current(first, second), ScheduleLogic.widgetState(schedule, monday("09:30")))
        assertEquals(WidgetState.Current(second, null), ScheduleLogic.widgetState(schedule, monday("11:00")))

        val beforeFirst = ScheduleLogic.widgetState(schedule, monday("08:00")) as WidgetState.Upcoming
        assertEquals(first, beforeFirst.next.universityClass)
        assertEquals(0, beforeFirst.next.dayOffset)

        val evening = ScheduleLogic.widgetState(schedule, monday("18:00")) as WidgetState.Upcoming
        assertEquals(tuesday, evening.next.universityClass)
        assertEquals(1, evening.next.dayOffset)

        assertEquals(WidgetState.NoClasses, ScheduleLogic.widgetState(emptyMap(), monday("09:30")))
    }

    @Test
    fun nextWidgetUpdate_followsClassBoundariesThenMidnight() {
        val first = lessonOn(Calendar.MONDAY, "9:00", "first").copy(endTime = "10:30")
        val schedule = mapOf(Calendar.MONDAY to listOf(first))

        assertEquals(monday("09:00").timeInMillis, ScheduleLogic.nextWidgetUpdate(schedule, monday("08:00")))
        assertEquals(monday("10:30").timeInMillis, ScheduleLogic.nextWidgetUpdate(schedule, monday("09:00")))
        val midnight = monday("00:00").apply { add(Calendar.DAY_OF_MONTH, 1) }
        assertEquals(midnight.timeInMillis, ScheduleLogic.nextWidgetUpdate(schedule, monday("10:30")))
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
