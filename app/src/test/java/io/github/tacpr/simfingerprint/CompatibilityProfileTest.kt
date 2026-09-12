package io.github.tacpr.simfingerprint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityProfileTest {
    @Test
    fun verifiedProfileRequiresDeviceVersionAndSlot() {
        assertTrue(
            CompatibilityProfile.isVerified(
                CompatibilityProfile.SUPPORTED_DEVICE,
                CompatibilityProfile.STK_PACKAGE,
                CompatibilityProfile.SUPPORTED_STK_VERSION,
                CompatibilityProfile.CONFIGURED_SLOT_ID,
            ),
        )
        assertFalse(
            CompatibilityProfile.isVerified(
                "OtherBrand",
                CompatibilityProfile.STK_PACKAGE,
                CompatibilityProfile.SUPPORTED_STK_VERSION,
                0,
            ),
        )
        assertFalse(
            CompatibilityProfile.isVerified(
                CompatibilityProfile.SUPPORTED_DEVICE,
                CompatibilityProfile.STK_PACKAGE,
                CompatibilityProfile.SUPPORTED_STK_VERSION,
                1,
            ),
        )
    }
}
