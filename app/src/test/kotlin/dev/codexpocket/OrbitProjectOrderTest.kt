package dev.codexpocket

import dev.codexpocket.core.ProjectInfo
import dev.codexpocket.core.json
import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.*

class OrbitProjectOrderTest {
    private val a = ProjectInfo("a", "A", listOf("/a"))
    private val b = ProjectInfo("b", "B", listOf("/b"))
    private val c = ProjectInfo("c", "C", listOf("/c"))
    private class MemoryStorage : PocketStorage {
        val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) { values[name] = value }
        override fun remove(name: String) { values.remove(name) }
    }

    @Test fun `order survives reload and discovery changes and is isolated by host`() {
        val storage = MemoryStorage()
        OrbitProjects(storage, "one").move(listOf(a, b), b.id, -1)
        val restored = OrbitProjects(storage, "one")
        assertEquals(listOf(b, a, c), restored.apply(listOf(c, a, b)))
        assertEquals(listOf(a, c), restored.apply(listOf(c, a)))
        assertEquals(listOf(a, b), OrbitProjects(storage, "two").apply(listOf(a, b)))
        restored.move(listOf(c, a, b), b.id, 1)
        assertEquals(listOf(a, b, c), restored.apply(listOf(c, b, a)))
    }

    @Test fun `legacy preferences retain order until moved including added projects`() {
        val legacy = json.decodeFromString<OrbitProjectPreferences>("""{"added":[],"removed":[]}""")
        assertEquals(listOf(b, a), legacy.apply(listOf(b, a)))
        val prefs = legacy.add(c).move(listOf(a, b), c.id, 1)
        assertEquals(listOf(a, c, b), prefs.apply(listOf(b, a)))
        assertEquals(listOf(a, b), prefs.remove(c).apply(listOf(c, b, a)))
    }

    @Test fun `boundary missing and invalid moves are harmless`() {
        val prefs = OrbitProjectPreferences()
        assertEquals(prefs, prefs.move(listOf(a, b), a.id, -1))
        assertEquals(prefs, prefs.move(listOf(a, b), b.id, 1))
        assertEquals(prefs, prefs.move(listOf(a, b), "missing", 1))
        assertEquals(prefs, prefs.move(listOf(a, b), a.id, 10))
    }
}
