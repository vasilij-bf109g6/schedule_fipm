package com.example.universityschedule

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class ScheduleNotFoundException(docId: String) : Exception("Документ $docId не найден")
class InvalidScheduleException(docId: String) : Exception("Некорректная структура данных в документе $docId")

// Очередное состояние документа расписания из подписки Firestore.
// fromCache — данные из локального кэша (нет связи с сервером или он ещё не ответил)
sealed class ScheduleUpdate {
    data class Loaded(val schedule: Map<Int, List<UniversityClass>>, val fromCache: Boolean) : ScheduleUpdate()
    data class Missing(val fromCache: Boolean) : ScheduleUpdate()
    object Invalid : ScheduleUpdate()
}

class ScheduleRepository {

    private val db = Firebase.firestore

    private fun document(subgroup: Int, interest: String) =
        db.collection("schedules").document("${subgroup}_$interest")

    // Подписка на документ: первое значение приходит из кэша почти сразу, дальше — каждое изменение на сервере.
    // MetadataChanges.INCLUDE нужен, чтобы узнать о переходе «кэш → сервер», даже если данные не изменились.
    fun observeSchedule(subgroup: Int, interest: String): Flow<ScheduleUpdate> = callbackFlow {
        val registration = document(subgroup, interest)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val fromCache = snapshot.metadata.isFromCache
                val update = if (!snapshot.exists()) {
                    ScheduleUpdate.Missing(fromCache)
                } else {
                    parse(snapshot)?.let { ScheduleUpdate.Loaded(it, fromCache) } ?: ScheduleUpdate.Invalid
                }
                trySend(update)
            }
        awaitClose { registration.remove() }
    }

    // Ручное обновление: идём именно на сервер, мимо кэша
    suspend fun fetchFromServer(subgroup: Int, interest: String): Map<Int, List<UniversityClass>> {
        val docId = "${subgroup}_$interest"
        val snapshot = document(subgroup, interest).get(Source.SERVER).await()
        if (!snapshot.exists()) throw ScheduleNotFoundException(docId)
        return parse(snapshot) ?: throw InvalidScheduleException(docId)
    }

    // null — документ заполнен с ошибкой
    private fun parse(document: DocumentSnapshot): Map<Int, List<UniversityClass>>? = try {
        parseOrThrow(document)
    } catch (e: ClassCastException) {
        // Например, вместо списка пар на день записана строка
        Log.w("Firestore", "Некорректная структура документа ${document.id}", e)
        null
    }

    private fun parseOrThrow(document: DocumentSnapshot): Map<Int, List<UniversityClass>>? {
        @Suppress("UNCHECKED_CAST")
        val scheduleData = document.get("schedule") as? Map<String, List<Map<String, Any>>>
            ?: return null

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
