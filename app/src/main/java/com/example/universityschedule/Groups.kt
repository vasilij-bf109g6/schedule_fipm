package com.example.universityschedule

// Группа студентов. id — имя документа расписания в коллекции schedules (например, "1_readers").
// Названия хранятся вместе с id, чтобы тулбар и виджет показывали их без сети.
data class Group(val id: String, val name: String, val shortName: String) {

    // Одна и та же группа, даже если в Firestore поменяли название
    fun isSameAs(other: Group?): Boolean = other != null && other.id == id
}

object Groups {

    // Разбор поля groups документа config/groups:
    //   groups: [ { id: "1_readers", name: "Подгруппа 1 - Чтецы", short: "Гр.1 Чтецы" }, ... ]
    // Записи без id или name пропускаем; short необязателен. null — поле отсутствует или пустое.
    fun parse(raw: Any?): List<Group>? {
        val items = raw as? List<*> ?: return null
        val groups = items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.trim().orEmpty()
            val name = (map["name"] as? String)?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
            val shortName = (map["short"] as? String)?.trim().orEmpty().ifEmpty { name }
            Group(id, name, shortName)
        }.distinctBy { it.id }
        return groups.ifEmpty { null }
    }
}
