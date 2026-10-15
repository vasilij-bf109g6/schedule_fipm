package com.example.universityschedule

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

// Копия расписания своей группы для напоминаний: они срабатывают, когда приложение закрыто
class ScheduleCache(context: Context) {

    private val prefs = context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)

    fun save(schedule: Map<Int, List<UniversityClass>>) {
        val days = JSONObject()
        schedule.forEach { (day, classes) ->
            val items = JSONArray()
            classes.forEach { item ->
                items.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("startTime", item.startTime)
                        .put("endTime", item.endTime)
                        .put("subject", item.subject)
                        .put("teacher", item.teacher)
                        .put("room", item.room)
                        .put("cancelled", item.cancelled)
                )
            }
            days.put(day.toString(), items)
        }
        prefs.edit { putString(KEY_SCHEDULE, days.toString()) }
    }

    fun load(): Map<Int, List<UniversityClass>> {
        val json = prefs.getString(KEY_SCHEDULE, null) ?: return emptyMap()
        return try {
            val days = JSONObject(json)
            days.keys().asSequence().associate { key ->
                val day = key.toInt()
                val items = days.getJSONArray(key)
                day to (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    UniversityClass(
                        id = item.optInt("id"),
                        startTime = item.optString("startTime"),
                        endTime = item.optString("endTime"),
                        subject = item.optString("subject"),
                        teacher = item.optString("teacher"),
                        room = item.optString("room"),
                        dayOfWeek = day,
                        cancelled = item.optBoolean("cancelled")
                    )
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // false — расписание своей группы ещё ни разу не загружалось
    fun hasData(): Boolean = prefs.contains(KEY_SCHEDULE)

    fun clear() {
        prefs.edit { remove(KEY_SCHEDULE) }
    }

    private companion object {
        const val KEY_SCHEDULE = "schedule_json"
    }
}
