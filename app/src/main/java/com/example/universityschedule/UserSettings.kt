package com.example.universityschedule

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit

enum class Interest(val key: String, @StringRes val titleRes: Int) {
    READERS("readers", R.string.interest_readers),
    SINGERS("singers", R.string.interest_singers);

    companion object {
        fun fromKey(key: String?): Interest = values().firstOrNull { it.key == key } ?: READERS
    }
}

data class Group(val subgroup: Int, val interest: Interest) {
    fun shortName(context: Context): String =
        context.getString(R.string.group_short, subgroup, context.getString(interest.titleRes))

    fun fullName(context: Context): String =
        context.getString(R.string.group_full, subgroup, context.getString(interest.titleRes))

    companion object {
        val ALL = listOf(1, 2).flatMap { subgroup -> Interest.values().map { Group(subgroup, it) } }
    }
}

// Обёртка над SharedPreferences: ключи совпадают со старыми, настройки пользователей сохранятся
class UserSettings(context: Context) {

    private val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    // Своя группа; null — пользователь ещё не выбирал
    var myGroup: Group?
        get() {
            if (!prefs.contains(KEY_SUBGROUP) || !prefs.contains(KEY_INTEREST)) return null
            return Group(prefs.getInt(KEY_SUBGROUP, 1), Interest.fromKey(prefs.getString(KEY_INTEREST, null)))
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_SUBGROUP)
                remove(KEY_INTEREST)
            } else {
                putInt(KEY_SUBGROUP, value.subgroup)
                putString(KEY_INTEREST, value.interest.key)
            }
        }

    // Группа, которую пользователь временно смотрит; null — смотрит свою
    var temporaryGroup: Group?
        get() {
            if (!prefs.getBoolean(KEY_TEMP_VIEW, false)) return null
            return Group(prefs.getInt(KEY_TEMP_SUBGROUP, 1), Interest.fromKey(prefs.getString(KEY_TEMP_INTEREST, null)))
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_TEMP_VIEW)
                remove(KEY_TEMP_SUBGROUP)
                remove(KEY_TEMP_INTEREST)
                // Остались от старых версий приложения
                remove("original_subgroup")
                remove("original_interest")
            } else {
                putBoolean(KEY_TEMP_VIEW, true)
                putInt(KEY_TEMP_SUBGROUP, value.subgroup)
                putString(KEY_TEMP_INTEREST, value.interest.key)
            }
        }

    // Группа, расписание которой сейчас на экране
    val activeGroup: Group?
        get() = temporaryGroup ?: myGroup

    // За сколько минут до пары напоминать; 0 — напоминания выключены
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_LEAD, 0)
        set(value) = prefs.edit { putInt(KEY_REMINDER_LEAD, value) }

    private companion object {
        const val KEY_SUBGROUP = "subgroup"
        const val KEY_INTEREST = "interest_group"
        const val KEY_TEMP_VIEW = "is_temporary_view"
        const val KEY_TEMP_SUBGROUP = "temp_subgroup"
        const val KEY_TEMP_INTEREST = "temp_interest"
        const val KEY_REMINDER_LEAD = "reminder_lead_minutes"
    }
}
