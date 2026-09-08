package com.betterlyrics.app.lyrics.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a pasted token is allowed to look like.
 *
 * Every one of these is copied out of a browser's developer tools, where the easy thing to copy is
 * the whole `Authorization` header. Prepending another `Bearer` to that produces an invalid header
 * and a 401 — which is indistinguishable from an expired token, so the user is sent hunting for a
 * fresh one that will fail in exactly the same way.
 */
class BearerValueTest {

    private val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJBQkMifQ.signature"

    @Test
    fun `a bare token is left alone`() {
        assertEquals(jwt, bearerValue(jwt))
    }

    @Test
    fun `a Bearer prefix is removed`() {
        assertEquals(jwt, bearerValue("Bearer $jwt"))
    }

    @Test
    fun `a whole header is reduced to the token`() {
        assertEquals(jwt, bearerValue("Authorization: Bearer $jwt"))
        // Developer tools copy the name in lower case.
        assertEquals(jwt, bearerValue("authorization: bearer $jwt"))
    }

    @Test
    fun `surrounding whitespace does not survive`() {
        assertEquals(jwt, bearerValue("  Bearer   $jwt \n"))
    }

    @Test
    fun `nothing is nothing`() {
        assertNull(bearerValue(null))
        assertNull(bearerValue(""))
        assertNull(bearerValue("   "))
        assertNull(bearerValue("Bearer "))
    }
}
