package io.github.judegibatron.phoneagent.util

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Small string-matching helpers for matching spoken names against contacts and app labels. */
object Fuzzy {

    private val nonWord = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(nonWord, " ").replace(spaces, " ").trim()

    /** 0.0 (no relation) to 1.0 (identical after normalisation). */
    fun score(query: String, candidate: String): Double {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        if (c.startsWith(q)) return 0.92
        if (c.contains(q)) return 0.85
        val qTokens = q.split(' ')
        val cTokens = c.split(' ')
        val overlap = qTokens.count { t -> cTokens.any { it == t || it.startsWith(t) || t.startsWith(it) } }
            .toDouble() / qTokens.size
        val similarity = 1.0 - levenshtein(q, c).toDouble() / max(q.length, c.length)
        return max(overlap * 0.8, similarity)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }

    /** Ranks candidates by [score] and returns those above [threshold], best first. */
    fun <T> rank(query: String, candidates: Iterable<T>, label: (T) -> String, threshold: Double = 0.55): List<Pair<T, Double>> =
        candidates.map { it to score(query, label(it)) }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
}
