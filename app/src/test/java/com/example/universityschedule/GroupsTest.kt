package com.example.universityschedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {

    @Test
    fun parse_readsGroupsInOrder() {
        val raw = listOf(
            mapOf("id" to "1_readers", "name" to "Подгруппа 1 - Чтецы", "short" to "Гр.1 Чтецы"),
            mapOf("id" to "2_singers", "name" to "Подгруппа 2 - Певцы")
        )

        assertEquals(
            listOf(
                Group("1_readers", "Подгруппа 1 - Чтецы", "Гр.1 Чтецы"),
                // short не указан — используем полное название
                Group("2_singers", "Подгруппа 2 - Певцы", "Подгруппа 2 - Певцы")
            ),
            Groups.parse(raw)
        )
    }

    @Test
    fun parse_skipsBrokenEntriesAndDuplicates() {
        val raw = listOf(
            mapOf("id" to "", "name" to "Без id"),
            mapOf("id" to "no_name"),
            "не объект",
            mapOf("id" to 42, "name" to "id не строка"),
            mapOf("id" to " 1_readers ", "name" to " Чтецы "),
            mapOf("id" to "1_readers", "name" to "Дубль")
        )

        assertEquals(listOf(Group("1_readers", "Чтецы", "Чтецы")), Groups.parse(raw))
    }

    @Test
    fun parse_returnsNullWhenNothingUsable() {
        assertNull(Groups.parse(null))
        assertNull(Groups.parse("строка"))
        assertNull(Groups.parse(emptyList<Any>()))
        assertNull(Groups.parse(listOf(mapOf("id" to "x"))))
    }

    @Test
    fun isSameAs_comparesById() {
        val group = Group("1_readers", "Подгруппа 1 - Чтецы", "Гр.1 Чтецы")
        assertTrue(group.isSameAs(group.copy(name = "Новое название")))
        assertFalse(group.isSameAs(Group("2_readers", "Подгруппа 1 - Чтецы", "Гр.1 Чтецы")))
        assertFalse(group.isSameAs(null))
    }
}
