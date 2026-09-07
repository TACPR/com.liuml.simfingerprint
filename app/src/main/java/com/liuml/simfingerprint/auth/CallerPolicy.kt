package com.liuml.simfingerprint.auth

import com.liuml.simfingerprint.CompatibilityProfile

data class CallerFacts(
    val callingUid: Int,
    val stkUid: Int,
    val callingUidOwnsStk: Boolean,
    val stkIsSystemApp: Boolean,
    val stkVersion: Long,
)

object CallerPolicy {
    fun rejectionReason(facts: CallerFacts): String? = when {
        facts.callingUid != facts.stkUid -> "uid_mismatch"
        !facts.callingUidOwnsStk -> "uid_packages_missing_stk"
        !facts.stkIsSystemApp -> "stk_not_system"
        facts.stkVersion != CompatibilityProfile.SUPPORTED_STK_VERSION -> "unsupported_version"
        else -> null
    }
}
