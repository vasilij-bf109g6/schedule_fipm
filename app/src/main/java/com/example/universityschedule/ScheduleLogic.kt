package com.example.universityschedule

import java.util.Calendar

// Чистая логика расписания без зависимостей от Android — покрыта unit-тестами

// Положение пары относительно текущего времени
enum class ClassTiming { PAST, CURRENT, FUTURE }

// Состояние учебного дня для строки статуса
sealed class DayStatus {
    object NoClasses : DayStatus()
    data class BeforeFirst(val first: UniversityClass) : DayStatus()
    data class InProgress(val current: UniversityClass, val minutesLeft: Int) : DayStatus()
    data class Break(val next: UniversityClass) : DayStatus()
    object Finished : DayStatus()
}

object ScheduleLogic {

    // Порядок дней в панели: ПН ... ВС
    val WEEK_DAYS = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )

    // "9:05" / "09:05" -> 545. Некорректная строка -> null
    fun toMinutes(time: String): Int? {
        val parts = time.trim().split(":")
        if (parts.size != 2) return null
        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    fun minutesOfDay(calendar: Calendar): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    // Сколько дней прошло от понедельника (ПН -> 0, ВС -> 6), независимо от локали
    fun daysFromMonday(dayOfWeek: Int): Int = (dayOfWeek - Calendar.MONDAY + 7) % 7

    // Пары с некорректным временем уходят в конец списка
    fun sortedByStart(classes: List<UniversityClass>): List<UniversityClass> =
        classes.sortedBy { toMinutes(it.startTime) ?: Int.MAX_VALUE }

    fun activeSorted(classes: List<UniversityClass>): List<UniversityClass> =
        sortedByStart(classes.filter { !it.cancelled })

    fun timing(universityClass: UniversityClass, nowMinutes: Int): ClassTiming {
        val start = toMinutes(universityClass.startTime) ?: return ClassTiming.FUTURE
        val end = toMinutes(universityClass.endTime) ?: return ClassTiming.FUTURE
        return when {
            nowMinutes >= end -> ClassTiming.PAST
            nowMinutes >= start -> ClassTiming.CURRENT
            else -> ClassTiming.FUTURE
        }
    }

    // Статус для сегодняшнего дня: отменённые пары не учитываются
    fun todayStatus(classes: List<UniversityClass>, nowMinutes: Int): DayStatus {
        val active = activeSorted(classes).filter {
            toMinutes(it.startTime) != null && toMinutes(it.endTime) != null
        }
        if (active.isEmpty()) return DayStatus.NoClasses

        active.firstOrNull { timing(it, nowMinutes) == ClassTiming.CURRENT }?.let { current ->
            val minutesLeft = toMinutes(current.endTime)!! - nowMinutes
            return DayStatus.InProgress(current, minutesLeft)
        }

        val next = active.firstOrNull { timing(it, nowMinutes) == ClassTiming.FUTURE }
        return when {
            next == null -> DayStatus.Finished
            next == active.first() -> DayStatus.BeforeFirst(next)
            else -> DayStatus.Break(next)
        }
    }
}
