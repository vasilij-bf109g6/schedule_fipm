package com.example.universityschedule

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.Calendar
import android.util.Log // Добавляем импорт для логирования

class ScheduleViewModel(private val repository: ScheduleRepository) : ViewModel() {

    private val _weeklySchedule = MutableLiveData<Map<Int, List<UniversityClass>>>()
    val weeklySchedule: LiveData<Map<Int, List<UniversityClass>>> = _weeklySchedule

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadSchedule(subgroup: Int, interest: String) {
        Log.d("ScheduleVM", "🔄 Загрузка расписания: подгруппа=$subgroup, интерес=$interest")
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val schedule = repository.getSchedule(subgroup, interest)
                Log.d("ScheduleVM", "📅 Расписание получено: ${schedule.size} дней")

                // Логируем количество отмененных пар
                schedule.forEach { (day, classes) ->
                    val cancelledCount = classes.count { it.cancelled }
                    if (cancelledCount > 0) {
                        Log.d("ScheduleVM", "День $day: ${classes.size} пар, из них отменено: $cancelledCount")
                    }
                }

                _weeklySchedule.value = schedule
            } catch (e: Exception) {
                Log.e("ScheduleVM", "💥 Ошибка: ${e.message}", e)
                _error.value = "Ошибка загрузки расписания: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getScheduleForDay(dayOfWeek: Int): List<UniversityClass> {
        return _weeklySchedule.value?.get(dayOfWeek) ?: emptyList()
    }

    /**
     * Получить текущую пару (игнорируя отмененные)
     * @param selectedDay выбранный день в календаре
     * @param currentDay текущий день недели
     * @return текущая активная пара или null
     */
    fun getCurrentClass(selectedDay: Int, currentDay: Int): UniversityClass? {
        if (selectedDay != currentDay) return null

        val schedule = getScheduleForDay(selectedDay)
        val currentTime = Calendar.getInstance()
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val currentTimeStr = timeFormat.format(currentTime.time)

        // Ищем только НЕ отмененные пары
        return schedule.find { classItem ->
            !classItem.cancelled &&  // Игнорируем отмененные пары
                    currentTimeStr >= classItem.startTime &&
                    currentTimeStr <= classItem.endTime
        }
    }

    /**
     * Получить следующую пару (игнорируя отмененные)
     * @param selectedDay выбранный день в календаре
     * @param currentDay текущий день недели
     * @return следующая активная пара или null
     */
    fun getNextClass(selectedDay: Int, currentDay: Int): UniversityClass? {
        if (selectedDay != currentDay) return null

        val schedule = getScheduleForDay(selectedDay)
        val currentTime = Calendar.getInstance()
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val currentTimeStr = timeFormat.format(currentTime.time)

        // Ищем только НЕ отмененные пары
        return schedule.find { classItem ->
            !classItem.cancelled &&  // Игнорируем отмененные пары
                    currentTimeStr < classItem.startTime
        }
    }

    /**
     * Получить первую пару дня (игнорируя отмененные)
     * @param dayOfWeek день недели
     * @return первая активная пара дня или null
     */
    fun getFirstClassOfDay(dayOfWeek: Int): UniversityClass? {
        val schedule = getScheduleForDay(dayOfWeek)
        return schedule
            .filter { !it.cancelled }  // Только не отмененные
            .minByOrNull { it.startTime }  // Самая ранняя по времени начала
    }

    /**
     * Получить последнюю пару дня (игнорируя отмененные)
     * @param dayOfWeek день недели
     * @return последняя активная пара дня или null
     */
    fun getLastClassOfDay(dayOfWeek: Int): UniversityClass? {
        val schedule = getScheduleForDay(dayOfWeek)
        return schedule
            .filter { !it.cancelled }  // Только не отмененные
            .maxByOrNull { it.endTime }  // Самая поздняя по времени окончания
    }

    /**
     * Получить все отмененные пары для указанного дня
     * @param dayOfWeek день недели
     * @return список отмененных пар
     */
    fun getCancelledClasses(dayOfWeek: Int): List<UniversityClass> {
        return getScheduleForDay(dayOfWeek)
            .filter { it.cancelled }
            .sortedBy { it.startTime }  // Сортировка по времени начала
    }

    /**
     * Проверить, есть ли активные (не отмененные) пары в указанный день
     * @param dayOfWeek день недели
     * @return true если есть хотя бы одна активная пара
     */
    fun hasActiveClasses(dayOfWeek: Int): Boolean {
        return getScheduleForDay(dayOfWeek)
            .any { !it.cancelled }
    }

    /**
     * Проверить, все ли пары в указанный день отменены
     * @param dayOfWeek день недели
     * @return true если все пары отменены
     */
    fun isAllClassesCancelled(dayOfWeek: Int): Boolean {
        val schedule = getScheduleForDay(dayOfWeek)
        return schedule.isNotEmpty() && schedule.all { it.cancelled }
    }

    /**
     * Получить количество активных пар в указанный день
     * @param dayOfWeek день недели
     * @return количество не отмененных пар
     */
    fun getActiveClassesCount(dayOfWeek: Int): Int {
        return getScheduleForDay(dayOfWeek)
            .count { !it.cancelled }
    }

    /**
     * Получить количество отмененных пар в указанный день
     * @param dayOfWeek день недели
     * @return количество отмененных пар
     */
    fun getCancelledClassesCount(dayOfWeek: Int): Int {
        return getScheduleForDay(dayOfWeek)
            .count { it.cancelled }
    }

    /**
     * Получить пару по её порядковому номеру в дне (игнорируя отмененные)
     * @param dayOfWeek день недели
     * @param position позиция (0-based)
     * @return пара на указанной позиции или null
     */
    fun getClassAtPosition(dayOfWeek: Int, position: Int): UniversityClass? {
        return getScheduleForDay(dayOfWeek)
            .filter { !it.cancelled }  // Только активные
            .sortedBy { it.startTime }  // Сортировка по времени
            .getOrNull(position)        // Получаем по позиции
    }

    /**
     * Получить ближайшую следующую пару от текущего времени
     * (используется для показа "Следующая пара через...")
     * @param selectedDay выбранный день
     * @param currentDay текущий день
     * @return ближайшая следующая активная пара
     */
    fun getNearestNextClass(selectedDay: Int, currentDay: Int): UniversityClass? {
        if (selectedDay != currentDay) return null

        val schedule = getScheduleForDay(selectedDay)
        val currentTime = Calendar.getInstance()
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val currentTimeStr = timeFormat.format(currentTime.time)

        return schedule
            .filter { !it.cancelled && currentTimeStr < it.startTime }  // Активные, которые еще не начались
            .minByOrNull { it.startTime }  // Ближайшая по времени начала
    }

    /**
     * Проверить, есть ли пары прямо сейчас (в текущий момент времени)
     * @param selectedDay выбранный день
     * @param currentDay текущий день
     * @return true если идет хоть одна активная пара
     */
    fun isClassInProgress(selectedDay: Int, currentDay: Int): Boolean {
        return getCurrentClass(selectedDay, currentDay) != null
    }

    /**
     * Получить список всех пар на день с учетом фильтрации
     * @param dayOfWeek день недели
     * @param showCancelled показывать ли отмененные пары
     * @return список пар
     */
    fun getClassesForDay(dayOfWeek: Int, showCancelled: Boolean = false): List<UniversityClass> {
        val schedule = getScheduleForDay(dayOfWeek)
        return if (showCancelled) {
            schedule.sortedBy { it.startTime }  // Все пары с сортировкой
        } else {
            schedule.filter { !it.cancelled }.sortedBy { it.startTime }  // Только активные
        }
    }
}