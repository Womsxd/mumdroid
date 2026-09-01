package dev.woms.mumdroid

import dev.woms.mumdroid.core.model.AccessTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessTokensTest {

    @Test
    fun add_ignoresBlankAndDuplicatesCaseInsensitively() {
        val first = AccessTokens.add(emptyList(), "  Secret  ")
        assertEquals(listOf("Secret"), first)
        assertEquals(first, AccessTokens.add(first, "secret"))
        assertEquals(first, AccessTokens.add(first, "   "))
        assertEquals(listOf("Secret", "other"), AccessTokens.add(first, "other"))
    }

    @Test
    fun sanitize_trimsDropsBlanksDedupesAndSorts() {
        assertEquals(
            listOf("alpha", "Secret"),
            AccessTokens.sanitize(listOf("  Secret  ", "", "alpha", "secret", "  ")),
        )
    }

    @Test
    fun replace_and_remove_areCaseInsensitive() {
        val tokens = listOf("Alpha", "Beta")
        assertEquals(listOf("Beta"), AccessTokens.remove(tokens, "alpha"))
        assertEquals(listOf("Beta", "Gamma"), AccessTokens.replace(tokens, "alpha", "Gamma"))
        assertEquals(listOf("Beta"), AccessTokens.replace(tokens, "Alpha", "beta"))
        assertEquals(listOf("Beta"), AccessTokens.replace(tokens, "Alpha", "   "))
    }
}
