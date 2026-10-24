package com.example.universityschedule

data class UniversityClass(
    val id: Int,
    val startTime: String,
    val endTime: String,
    val subject: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,
    // Отменена на всех неделях
    val cancelled: Boolean = false,
    // Даты разовых отмен в формате "2026-10-27"
    val cancelledDates: Set<String> = emptySet()
)
