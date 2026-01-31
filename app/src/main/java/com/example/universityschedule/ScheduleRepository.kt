package com.example.universityschedule

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

class ScheduleRepository(private val context: Context) {

    private val db = Firebase.firestore

    suspend fun getSchedule(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("Firestore", "⚡ Начинаю загрузку из Firestore для $subgroup, $interest")
                val result = loadFromFirestore(subgroup, interest)
                Log.d("Firestore", "✅ Успешно загружено из Firestore: ${result.size} дней")
                result
            } catch (e: Exception) {
                Log.e("Firestore", "🔥 ОШИБКА Firestore: ${e.message}", e)
                Log.d("Firestore", "🔄 Использую локальное расписание из-за ошибки")
                getLocalSchedule(subgroup, interest)
            }
        }
    }

    private suspend fun loadFromFirestore(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        val docId = "${subgroup}_$interest"
        Log.d("Firestore", "📄 Ищу документ: $docId в коллекции 'schedules'")

        val document = db.collection("schedules").document(docId).get().await()

        Log.d("Firestore", "📊 Документ $docId существует? ${document.exists()}")

        if (!document.exists()) {
            Log.w("Firestore", "❌ Документ $docId не найден в Firestore")
            throw Exception("Документ $docId не найден")
        }

        val scheduleMap = mutableMapOf<Int, List<UniversityClass>>()
        val scheduleData = document.get("schedule") as? Map<String, List<Map<String, Any>>>

        if (scheduleData == null) {
            Log.w("Firestore", "⚠️ Некорректная структура данных в документе $docId")
            throw Exception("Некорректная структура данных")
        }

        // Преобразуем данные Firestore в Map<Int, List<UniversityClass>>
        scheduleData.forEach { (dayName, classesList) ->
            val dayOfWeek = when (dayName.lowercase()) {
                "monday" -> Calendar.MONDAY
                "tuesday" -> Calendar.TUESDAY
                "wednesday" -> Calendar.WEDNESDAY
                "thursday" -> Calendar.THURSDAY
                "friday" -> Calendar.FRIDAY
                "saturday" -> Calendar.SATURDAY
                "sunday" -> Calendar.SUNDAY
                else -> {
                    Log.w("Firestore", "⚠️ Неизвестный день: $dayName")
                    return@forEach
                }
            }

            val universityClasses = classesList.mapNotNull { classData ->
                try {
                    UniversityClass(
                        id = (classData["id"] as? Long)?.toInt() ?: 0,
                        startTime = classData["startTime"] as? String ?: "",
                        endTime = classData["endTime"] as? String ?: "",
                        subject = classData["subject"] as? String ?: "",
                        teacher = classData["teacher"] as? String ?: "",
                        room = classData["room"] as? String ?: "",
                        dayOfWeek = dayOfWeek,
                        cancelled = classData["cancelled"] as? Boolean ?: false
                    )
                } catch (e: Exception) {
                    Log.e("Firestore", "❌ Ошибка преобразования данных пары: ${e.message}")
                    null
                }
            }

            val filteredClasses = universityClasses.filter { !it.cancelled }

            scheduleMap[dayOfWeek] = filteredClasses

            if (universityClasses.isNotEmpty()) {
                scheduleMap[dayOfWeek] = universityClasses
                Log.d("Firestore", "📅 Добавлен день $dayName: ${universityClasses.size} пар")
            }
        }

        Log.d("Firestore", "🎉 Загружено расписание: ${scheduleMap.size} дней")
        return scheduleMap
    }

    // Оставляем локальное расписание как fallback
    private fun getLocalSchedule(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        Log.d("ScheduleRepository", "📂 Используется локальное расписание (fallback)")
        return mapOf(

            )

    }
}