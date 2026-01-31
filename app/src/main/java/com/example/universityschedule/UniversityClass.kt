package com.example.universityschedule

data class UniversityClass(
    val id: Int,
    val startTime: String,
    val endTime: String,
    val subject: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,
    val cancelled: Boolean = false  // ← Добавляем поле
)