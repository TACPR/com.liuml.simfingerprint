package com.liuml.simfingerprint.xposed

import com.liuml.simfingerprint.CompatibilityProfile

data class PromptFacts(
    val activityClass: String,
    val prompt: String,
    val slotId: Int,
    val isPasswordInput: Boolean,
    val inputIsEmpty: Boolean,
    val minLength: Int,
    val maxLength: Int,
    val hasWindowFocus: Boolean,
    val stkVersion: Long,
    val fieldsCompatible: Boolean,
    val deviceModel: String = CompatibilityProfile.SUPPORTED_DEVICE,
)

data class PromptDecision(val eligible: Boolean, val reason: String)

object PromptMatcher {
    fun evaluate(facts: PromptFacts): PromptDecision {
        return when {
            facts.activityClass != CompatibilityProfile.STK_ACTIVITY -> reject("wrong_activity")
            facts.stkVersion != CompatibilityProfile.SUPPORTED_STK_VERSION -> reject("unsupported_version")
            facts.slotId != CompatibilityProfile.CONFIGURED_SLOT_ID -> reject("wrong_slot")
            !CompatibilityProfile.isVerified(
                facts.deviceModel,
                CompatibilityProfile.STK_PACKAGE,
                facts.stkVersion,
                facts.slotId,
            ) -> reject("unverified_device_or_slot")
            !facts.fieldsCompatible -> reject("fields_incompatible")
            !facts.hasWindowFocus -> reject("not_focused")
            !facts.isPasswordInput -> reject("not_password")
            !facts.inputIsEmpty -> reject("input_not_empty")
            facts.minLength > 6 || facts.maxLength < 6 -> reject("length_not_supported")
            else -> PromptDecision(true, "stk_password_input")
        }
    }

    private fun reject(reason: String) = PromptDecision(false, reason)
}
