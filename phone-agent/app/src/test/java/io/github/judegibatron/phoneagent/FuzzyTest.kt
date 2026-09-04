package io.github.judegibatron.phoneagent

import io.github.judegibatron.phoneagent.util.Fuzzy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun `exact and near matches score high`() {
        assertEquals(1.0, Fuzzy.score("Spotify", "spotify"), 0.0)
        assertTrue(Fuzzy.score("mom", "Mom") == 1.0)
        assertTrue(Fuzzy.score("audible", "Audible: audiobooks") > 0.9)
        assertTrue(Fuzzy.score("john", "John Smith") > 0.9)
    }

    @Test
    fun `speech mangling still ranks the right contact first`() {
        val contacts = listOf("Sarah Connor", "Sara Khan", "Steve Jobs", "Mom")
        val ranked = Fuzzy.rank("sarah conner", contacts, { it })
        assertEquals("Sarah Connor", ranked.first().first)
    }

    @Test
    fun `unrelated strings score low`() {
        assertTrue(Fuzzy.score("flashlight", "Spotify") < 0.5)
    }

    @Test
    fun `levenshtein basics`() {
        assertEquals(0, Fuzzy.levenshtein("abc", "abc"))
        assertEquals(1, Fuzzy.levenshtein("abc", "abd"))
        assertEquals(3, Fuzzy.levenshtein("", "abc"))
    }
}
