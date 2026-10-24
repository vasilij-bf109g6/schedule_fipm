package com.example.universityschedule

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// Встроенный список групп: показываем, пока список из Firestore не загрузился или нет сети
fun defaultGroups(context: Context): List<Group> =
    listOf(1, 2).flatMap { subgroup ->
        listOf("readers" to R.string.interest_readers, "singers" to R.string.interest_singers).map { (key, titleRes) ->
            val title = context.getString(titleRes)
            Group(
                id = "${subgroup}_$key",
                name = context.getString(R.string.group_list_item, subgroup, title),
                shortName = context.getString(R.string.group_short, subgroup, title)
            )
        }
    }

// Обёртка над SharedPreferences. Группа хранится как id + названия;
// настройки старых версий (подгруппа + чтецы/певцы) переводятся в этот формат при первом чтении
class UserSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyKeys()
    }

    // Своя группа; null — пользователь ещё не выбирал
    var myGroup: Group?
        get() = readGroup(KEY_GROUP_ID, KEY_GROUP_NAME, KEY_GROUP_SHORT)
        set(value) = prefs.edit { writeGroup(value, KEY_GROUP_ID, KEY_GROUP_NAME, KEY_GROUP_SHORT) }

    // Группа, которую пользователь временно смотрит; null — смотрит свою
    var temporaryGroup: Group?
        get() = readGroup(KEY_TEMP_ID, KEY_TEMP_NAME, KEY_TEMP_SHORT)
        set(value) = prefs.edit { writeGroup(value, KEY_TEMP_ID, KEY_TEMP_NAME, KEY_TEMP_SHORT) }

    // Группа, расписание которой сейчас на экране
    val activeGroup: Group?
        get() = temporaryGroup ?: myGroup

    // За сколько минут до пары напоминать; 0 — напоминания выключены
    var reminderLeadMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_LEAD, 0)
        set(value) = prefs.edit { putInt(KEY_REMINDER_LEAD, value) }

    // Названия в Firestore поменяли — обновляем сохранённые, чтобы тулбар и виджет показывали новые
    fun updateNames(groups: List<Group>) {
        myGroup?.let { mine -> groups.firstOrNull { it.isSameAs(mine) }?.let { if (it != mine) myGroup = it } }
        temporaryGroup?.let { temp -> groups.firstOrNull { it.isSameAs(temp) }?.let { if (it != temp) temporaryGroup = it } }
    }

    private fun readGroup(idKey: String, nameKey: String, shortKey: String): Group? {
        val id = prefs.getString(idKey, null) ?: return null
        val name = prefs.getString(nameKey, null) ?: id
        return Group(id, name, prefs.getString(shortKey, null) ?: name)
    }

    private fun SharedPreferences.Editor.writeGroup(group: Group?, idKey: String, nameKey: String, shortKey: String) {
        if (group == null) {
            remove(idKey)
            remove(nameKey)
            remove(shortKey)
        } else {
            putString(idKey, group.id)
            putString(nameKey, group.name)
            putString(shortKey, group.shortName)
        }
    }

    private fun migrateLegacyKeys() {
        val hasLegacyGroup = prefs.contains(LEGACY_SUBGROUP) && prefs.contains(LEGACY_INTEREST)
        val hasLegacyTemp = prefs.getBoolean(LEGACY_TEMP_VIEW, false)
        if (!hasLegacyGroup && !prefs.contains(LEGACY_TEMP_VIEW)) return

        val defaults = defaultGroups(context)
        fun legacyGroup(subgroup: Int, interest: String?): Group {
            val id = "${subgroup}_${interest ?: "readers"}"
            return defaults.firstOrNull { it.id == id } ?: Group(id, id, id)
        }

        prefs.edit {
            if (hasLegacyGroup && !prefs.contains(KEY_GROUP_ID)) {
                writeGroup(
                    legacyGroup(prefs.getInt(LEGACY_SUBGROUP, 1), prefs.getString(LEGACY_INTEREST, null)),
                    KEY_GROUP_ID, KEY_GROUP_NAME, KEY_GROUP_SHORT
                )
            }
            if (hasLegacyTemp && !prefs.contains(KEY_TEMP_ID)) {
                writeGroup(
                    legacyGroup(prefs.getInt(LEGACY_TEMP_SUBGROUP, 1), prefs.getString(LEGACY_TEMP_INTEREST, null)),
                    KEY_TEMP_ID, KEY_TEMP_NAME, KEY_TEMP_SHORT
                )
            }
            listOf(
                LEGACY_SUBGROUP, LEGACY_INTEREST, LEGACY_TEMP_VIEW, LEGACY_TEMP_SUBGROUP, LEGACY_TEMP_INTEREST,
                "original_subgroup", "original_interest"
            ).forEach { remove(it) }
        }
    }

    private companion object {
        const val KEY_GROUP_ID = "group_id"
        const val KEY_GROUP_NAME = "group_name"
        const val KEY_GROUP_SHORT = "group_short"
        const val KEY_TEMP_ID = "temp_group_id"
        const val KEY_TEMP_NAME = "temp_group_name"
        const val KEY_TEMP_SHORT = "temp_group_short"
        const val KEY_REMINDER_LEAD = "reminder_lead_minutes"

        // Ключи версий до списка групп из Firestore
        const val LEGACY_SUBGROUP = "subgroup"
        const val LEGACY_INTEREST = "interest_group"
        const val LEGACY_TEMP_VIEW = "is_temporary_view"
        const val LEGACY_TEMP_SUBGROUP = "temp_subgroup"
        const val LEGACY_TEMP_INTEREST = "temp_interest"
    }
}
