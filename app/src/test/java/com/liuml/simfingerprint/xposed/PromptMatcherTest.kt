package com.liuml.simfingerprint.xposed

import com.liuml.simfingerprint.CompatibilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptMatcherTest {
    private val valid = PromptFacts(
        activityClass = CompatibilityProfile.STK_ACTIVITY,
        prompt = "您好，您正在通过手机登录的方式登录中国移动云盘，请确认是否继续进行登录操作。",
        slotId = 0,
        isPasswordInput = true,
        inputIsEmpty = true,
        minLength = 6,
        maxLength = 8,
        hasWindowFocus = true,
        stkVersion = CompatibilityProfile.SUPPORTED_STK_VERSION,
        fieldsCompatible = true,
    )

    @Test
    fun realCapturedLoginPromptMatches() {
        assertTrue(PromptMatcher.evaluate(valid).eligible)
    }

    @Test
    fun anotherProductUsingSameLoginTemplateMatches() {
        val result = PromptMatcher.evaluate(valid.copy(prompt = "您好，您正在通过手机登录的方式登录统一办公门户，请确认是否继续进行登录操作。"))
        assertTrue(result.eligible)
    }

    @Test
    fun arbitraryPromptOnStkPasswordPageMatches() {
        val result = PromptMatcher.evaluate(
            valid.copy(prompt = "请输入六位 PIN 完成操作"),
        )
        assertTrue(result.eligible)
    }

    @Test
    fun transactionPromptAlsoMatchesWhenStructuralChecksPass() {
        val result = PromptMatcher.evaluate(valid.copy(prompt = "您好，您正在通过手机登录的方式登录转账交易，请确认是否继续进行登录操作。"))
        assertTrue(result.eligible)
    }

    @Test
    fun wrongSlotVersionOrExistingInputIsRejected() {
        assertEquals("wrong_slot", PromptMatcher.evaluate(valid.copy(slotId = 1)).reason)
        assertEquals("unsupported_version", PromptMatcher.evaluate(valid.copy(stkVersion = 1L)).reason)
        assertEquals("input_not_empty", PromptMatcher.evaluate(valid.copy(inputIsEmpty = false)).reason)
    }

    @Test
    fun unknownDeviceIsRejectedEvenWhenStkLooksCompatible() {
        assertEquals(
            "unverified_device_or_slot",
            PromptMatcher.evaluate(valid.copy(deviceModel = "OtherBrand" )).reason,
        )
    }

    @Test
    fun nonPasswordAndUnsupportedLengthAreRejected() {
        assertEquals("not_password", PromptMatcher.evaluate(valid.copy(isPasswordInput = false)).reason)
        assertEquals("length_not_supported", PromptMatcher.evaluate(valid.copy(minLength = 7)).reason)
    }
}
