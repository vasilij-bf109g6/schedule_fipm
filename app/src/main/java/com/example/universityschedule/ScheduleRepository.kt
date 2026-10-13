package com.example.universityschedule

import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

class ScheduleNotFoundException(docId: String) : Exception("Документ $docId не найден")
class InvalidScheduleException(docId: String) : Exception("Некорректная структура данных в документе $docId")

class ScheduleRepository {

    private val db = Firebase.firestore

    // Бросает исключение при ошибке — ViewModel покажет её пользователю.
    // Без сети Firestore сам вернёт закэшированную копию, если она есть.
    suspend fun getSchedule(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        return withContext(Dispatchers.IO) {
            val result = loadFromFirestore(subgroup, interest)
            Log.d("Firestore", "Загружено расписание: ${result.size} дней")
            result
        }
    }

    private suspend fun loadFromFirestore(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        val docId = "${subgroup}_$interest"
        val document = db.collection("schedules").document(docId).get().await()

        if (!document.exists()) throw ScheduleNotFoundException(docId)

        @Suppress("UNCHECKED_CAST")
        val scheduleData = document.get("schedule") as? Map<String, List<Map<String, Any>>>
            ?: throw InvalidScheduleException(docId)

        val scheduleMap = mutableMapOf<Int, List<UniversityClass>>()
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
                    Log.w("Firestore", "Неизвестный день: $dayName")
                    return@forEach
                }
            }

            val universityClasses = classesList.map { classData ->
                UniversityClass(
                    id = (classData["id"] as? Number)?.toInt() ?: 0,
                    startTime = classData["startTime"] as? String ?: "",
                    endTime = classData["endTime"] as? String ?: "",
                    subject = classData["subject"] as? String ?: "",
                    teacher = classData["teacher"] as? String ?: "",
                    room = classData["room"] as? String ?: "",
                    dayOfWeek = dayOfWeek,
                    cancelled = classData["cancelled"] as? Boolean ?: false
                )
            }

            scheduleMap[dayOfWeek] = ScheduleLogic.sortedByStart(universityClasses)
        }

        return scheduleMap
    }
}
