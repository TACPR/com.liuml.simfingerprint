package com.liuml.simfingerprint.auth

import com.liuml.simfingerprint.CompatibilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerPolicyTest {
    @Test
    fun exactSystemStkCallerIsAccepted() {
        assertNull(
            CallerPolicy.rejectionReason(
                CallerFacts(1001, 1001, true, true, CompatibilityProfile.SUPPORTED_STK_VERSION),
            ),
        )
    }

    @Test
    fun wrongUidNonSystemAndWrongVersionAreRejected() {
        assertEquals("uid_mismatch", CallerPolicy.rejectionReason(CallerFacts(1234, 1001, true, true, CompatibilityProfile.SUPPORTED_STK_VERSION)))
        assertEquals("uid_packages_missing_stk", CallerPolicy.rejectionReason(CallerFacts(1001, 1001, false, true, CompatibilityProfile.SUPPORTED_STK_VERSION)))
        assertEquals("stk_not_system", CallerPolicy.rejectionReason(CallerFacts(1001, 1001, true, false, CompatibilityProfile.SUPPORTED_STK_VERSION)))
        assertEquals("unsupported_version", CallerPolicy.rejectionReason(CallerFacts(1001, 1001, true, true, 1L)))
    }
}
