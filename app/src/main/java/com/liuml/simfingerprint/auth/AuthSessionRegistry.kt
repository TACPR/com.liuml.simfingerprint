package com.liuml.simfingerprint.auth

import android.os.SystemClock

object AuthSessionRegistry {
    private val store = SessionStore(now = SystemClock::elapsedRealtime)

    fun begin(): String = store.begin()
    fun isPending(id: String): Boolean = store.isPending(id)
    fun markAuthenticated(id: String, secret: ByteArray): Boolean = store.markAuthenticated(id, secret)
    fun consume(id: String): SessionStore.ConsumeResult = store.consume(id)
    fun cancel(id: String): Boolean = store.cancel(id)
}

