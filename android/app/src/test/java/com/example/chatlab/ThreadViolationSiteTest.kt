package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThreadViolationSiteTest {
    private val own = "com.example.chatlab.Search.onCreate(Search.kt:12)"
    private val framework = "android.os.StrictMode.noteRead(StrictMode.java:99)"

    @Test
    fun the_application_lines_come_first_and_few_lines_are_kept() {
        val all = listOf(framework) + List(6) { "com.example.chatlab.Screen.f$it(Screen.kt:$it)" } + List(5) { "java.io.File.x$it" }
        val where = ThreadViolationSite.frames(all)
        assertEquals(4 + 2, where.size)
        assertEquals(all[1], where[0])
        assertEquals(true, where.take(4).all { it.contains("com.example.chatlab") })
    }

    @Test
    fun a_violation_without_application_lines_still_has_its_first_lines() {
        val where = ThreadViolationSite.frames(listOf(framework, "java.io.File.read(File.java:1)"))
        assertEquals(listOf(framework, "java.io.File.read(File.java:1)"), where)
    }

    @Test
    fun the_same_place_gives_the_same_key_and_another_place_another_one() {
        val a = ThreadViolationSite.key("DiskReadViolation", listOf(own))
        assertEquals(a, ThreadViolationSite.key("DiskReadViolation", listOf(own, "other line")))
        assertNotEquals(a, ThreadViolationSite.key("DiskReadViolation", listOf("com.example.chatlab.Other.g(Other.kt:3)")))
        assertNotEquals(a, ThreadViolationSite.key("NetworkViolation", listOf(own)))
    }

    @Test
    fun the_json_names_the_kind_and_lists_the_lines() {
        val json = ThreadViolationSite.json("DiskReadViolation", listOf(own))
        assertEquals("DiskReadViolation", json.getString("kind"))
        assertEquals(own, json.getJSONArray("where").getString(0))
    }
}
