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

// Напоминание о паре: когда показать и когда пара начинается (миллисекунды epoch).
// dayOffset — через сколько дней от сегодня пара (0 — сегодня)
data class Reminder(
    val universityClass: UniversityClass,
    val triggerAtMillis: Long,
    val startAtMillis: Long,
    val dayOffset: Int
)

// Что показать в виджете
sealed class WidgetState {
    object NoClasses : WidgetState()
    // Идёт пара; nextToday — следующая пара сегодня, если есть
    data class Current(val current: UniversityClass, val nextToday: UniversityClass?) : WidgetState()
    data class Upcoming(val next: Reminder) : WidgetState()
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

    // Страницы свайпа: текущая неделя (0..6) и следующая (7..13), от понедельника
    const val PAGE_COUNT = 14

    fun pageFor(weekOffset: Int, dayOfWeek: Int): Int = weekOffset * 7 + daysFromMonday(dayOfWeek)

    fun dayForPage(page: Int): Int = WEEK_DAYS[page % 7]

    fun weekOffsetForPage(page: Int): Int = page / 7

    // Дата страницы относительно сегодняшнего дня (время суток сохраняется)
    fun dateForPage(page: Int, today: Calendar): Calendar = (today.clone() as Calendar).apply {
        add(Calendar.DAY_OF_MONTH, page - daysFromMonday(today.get(Calendar.DAY_OF_WEEK)))
    }

    // "2026-10-27" — формат дат в поле cancelledDates. Без SimpleDateFormat, чтобы не зависеть от локали
    fun dateKey(date: Calendar): String = String.format(
        java.util.Locale.ROOT, "%04d-%02d-%02d",
        date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH)
    )

    // Пары дня с учётом разовых отмен на эту дату: такие пары помечаются cancelled = true,
    // поэтому вся остальная логика (статус, напоминания, виджет) работает без изменений
    fun classesOn(schedule: Map<Int, List<UniversityClass>>, date: Calendar): List<UniversityClass> {
        val key = dateKey(date)
        return schedule[date.get(Calendar.DAY_OF_WEEK)].orEmpty().map {
            if (!it.cancelled && key in it.cancelledDates) it.copy(cancelled = true) else it
        }
    }

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

    // Ближайшее напоминание после now. Расписание повторяется каждую неделю,
    // поэтому смотрим 8 дней вперёд: сегодня и тот же день недели через неделю.
    // Напоминания, время которых уже прошло, пропускаем — опоздавшее уведомление бесполезно.
    fun nextReminder(schedule: Map<Int, List<UniversityClass>>, now: Calendar, leadMinutes: Int): Reminder? {
        if (leadMinutes <= 0) return null
        return findNext(schedule, now, leadMinutes)
    }

    // Ближайшая пара, которая ещё не началась (на любой день вперёд)
    fun upcomingClass(schedule: Map<Int, List<UniversityClass>>, now: Calendar): Reminder? =
        findNext(schedule, now, 0)

    private fun findNext(schedule: Map<Int, List<UniversityClass>>, now: Calendar, leadMinutes: Int): Reminder? {
        val nowMillis = now.timeInMillis

        for (dayOffset in 0..7) {
            val day = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val classes = activeSorted(classesOn(schedule, day))

            for (universityClass in classes) {
                val start = toMinutes(universityClass.startTime) ?: continue
                val startAt = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, start / 60)
                    set(Calendar.MINUTE, start % 60)
                }.timeInMillis
                val triggerAt = startAt - leadMinutes * 60_000L
                if (triggerAt > nowMillis) return Reminder(universityClass, triggerAt, startAt, dayOffset)
            }
        }
        return null
    }

    fun widgetState(schedule: Map<Int, List<UniversityClass>>, now: Calendar): WidgetState {
        val today = classesOn(schedule, now)
        val status = todayStatus(today, minutesOfDay(now))
        val upcoming = upcomingClass(schedule, now)

        return when {
            status is DayStatus.InProgress ->
                WidgetState.Current(status.current, upcoming?.takeIf { it.dayOffset == 0 }?.universityClass)
            upcoming != null -> WidgetState.Upcoming(upcoming)
            else -> WidgetState.NoClasses
        }
    }

    // Когда виджет нужно перерисовать: ближайшее начало или конец пары сегодня, иначе полночь
    fun nextWidgetUpdate(schedule: Map<Int, List<UniversityClass>>, now: Calendar): Long {
        val nowMinutes = minutesOfDay(now)
        val boundary = activeSorted(classesOn(schedule, now))
            .flatMap { listOfNotNull(toMinutes(it.startTime), toMinutes(it.endTime)) }
            .filter { it > nowMinutes }
            .minOrNull()

        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (boundary != null) {
                set(Calendar.HOUR_OF_DAY, boundary / 60)
                set(Calendar.MINUTE, boundary % 60)
            } else {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }
        }.timeInMillis
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
