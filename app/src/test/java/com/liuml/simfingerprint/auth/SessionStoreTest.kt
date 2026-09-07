package com.liuml.simfingerprint.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test
    fun authenticatedSecretCanOnlyBeConsumedOnce() {
        var now = 1_000L
        val store = SessionStore(now = { now })
        val id = store.begin()
        val secret = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertTrue(store.markAuthenticated(id, secret))
        val first = store.consume(id)
        assertEquals("ok", first.status)
        assertArrayEquals(secret, first.secret)
        assertEquals("expired", store.consume(id).status)
    }

    @Test
    fun pendingAndSecretTtlAreEnforced() {
        var now = 1_000L
        val store = SessionStore(now = { now }, pendingTtlMillis = 100L, secretTtlMillis = 10L)
        val pending = store.begin()
        now += 101L
        assertFalse(store.isPending(pending))

        val authenticated = store.begin()
        assertTrue(store.markAuthenticated(authenticated, byteArrayOf(1)))
        now += 11L
        assertEquals("expired", store.consume(authenticated).status)
    }

    @Test
    fun cancelledSessionCannotBeConsumed() {
        val store = SessionStore(now = { 1_000L })
        val id = store.begin()
        assertTrue(store.cancel(id))
        assertEquals("expired", store.consume(id).status)
    }
}

