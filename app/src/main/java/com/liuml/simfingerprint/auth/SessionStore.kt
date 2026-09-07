package com.liuml.simfingerprint.auth

import java.security.SecureRandom
import java.util.Base64

class SessionStore(
    private val now: () -> Long,
    private val pendingTtlMillis: Long = 60_000L,
    private val secretTtlMillis: Long = 10_000L,
) {
    private enum class State { PENDING, AUTHENTICATED, CANCELLED }

    private data class Session(
        val createdAt: Long,
        var authenticatedAt: Long = 0L,
        var state: State = State.PENDING,
        var secret: ByteArray? = null,
    )

    data class ConsumeResult(val status: String, val secret: ByteArray? = null)

    private val random = SecureRandom()
    private val sessions = mutableMapOf<String, Session>()

    @Synchronized
    fun begin(): String {
        purgeExpired()
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        bytes.fill(0)
        sessions[id] = Session(createdAt = now())
        return id
    }

    @Synchronized
    fun isPending(id: String): Boolean {
        purgeExpired()
        return sessions[id]?.state == State.PENDING
    }

    @Synchronized
    fun markAuthenticated(id: String, secret: ByteArray): Boolean {
        purgeExpired()
        val session = sessions[id] ?: return false
        if (session.state != State.PENDING) return false
        session.secret?.fill(0)
        session.secret = secret
        session.authenticatedAt = now()
        session.state = State.AUTHENTICATED
        return true
    }

    @Synchronized
    fun consume(id: String): ConsumeResult {
        purgeExpired()
        val session = sessions[id] ?: return ConsumeResult("expired")
        return when (session.state) {
            State.PENDING -> ConsumeResult("pending")
            State.CANCELLED -> {
                sessions.remove(id)
                ConsumeResult("cancelled")
            }
            State.AUTHENTICATED -> {
                sessions.remove(id)
                val secret = session.secret
                session.secret = null
                ConsumeResult("ok", secret)
            }
        }
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.secret?.fill(0)
        session.secret = null
        session.state = State.CANCELLED
        return true
    }

    @Synchronized
    fun clear() {
        sessions.values.forEach { it.secret?.fill(0) }
        sessions.clear()
    }

    private fun purgeExpired() {
        val current = now()
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val (_, session) = iterator.next()
            val expired = when (session.state) {
                State.PENDING -> current - session.createdAt > pendingTtlMillis
                State.AUTHENTICATED -> current - session.authenticatedAt > secretTtlMillis
                State.CANCELLED -> true
            }
            if (expired) {
                session.secret?.fill(0)
                session.secret = null
                iterator.remove()
            }
        }
    }
}

