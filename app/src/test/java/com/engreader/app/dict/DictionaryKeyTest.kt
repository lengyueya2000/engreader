package com.engreader.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The headword keys a tapped token is looked up under.
 *
 * The reader hands the dictionary the raw token, so everything a tap can pick up —
 * surrounding punctuation, a possessive ending — is handled here. Order matters: the
 * token itself is tried first, because `it's` and `don't` are headwords of their own.
 */
class DictionaryKeyTest {

    @Test
    fun `a possessive falls back to its headword`() {
        // Neither `minister's` nor `ministers'` is in the dictionary, so the second key
        // is what makes a tap on either find anything at all.
        assertEquals(listOf("minister's", "minister"), Dictionary.lookupKeys("minister's"))
        assertEquals(listOf("minister\u2019s", "minister"), Dictionary.lookupKeys("minister\u2019s"))
        assertEquals(listOf("ministers"), Dictionary.lookupKeys("ministers'"))
        assertEquals(listOf("ministers"), Dictionary.lookupKeys("ministers\u2019"))
    }

    @Test
    fun `a contraction is not reduced to its stem`() {
        // `it's` and `don't` are entries in their own right, so the exact token has to
        // be tried before the possessive fallback or their gloss is never reached.
        assertEquals(listOf("it's", "it"), Dictionary.lookupKeys("it's"))
        assertEquals(listOf("don't"), Dictionary.lookupKeys("don't"))
    }

    @Test
    fun `surrounding punctuation goes`() {
        assertEquals(listOf("word"), Dictionary.lookupKeys("word."))
        assertEquals(listOf("word"), Dictionary.lookupKeys("\u201Cword\u201D"))
        assertEquals(listOf("word"), Dictionary.lookupKeys("(word)"))
        assertEquals(listOf("word"), Dictionary.lookupKeys("  word,  "))
    }

    @Test
    fun `a token with nothing to look up yields no keys`() {
        assertTrue(Dictionary.lookupKeys("").isEmpty())
        assertTrue(Dictionary.lookupKeys("   ").isEmpty())
        assertTrue(Dictionary.lookupKeys("...").isEmpty())
        assertTrue(Dictionary.lookupKeys("'").isEmpty())
    }
}
