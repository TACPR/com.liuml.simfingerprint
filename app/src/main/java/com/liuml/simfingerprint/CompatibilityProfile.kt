package com.liuml.simfingerprint

object CompatibilityProfile {
    const val STK_PACKAGE = "com.android.stk"
    const val PHONE_PACKAGE = "com.android.phone"
    const val STK_ACTIVITY = "com.android.stk.StkInputActivity"
    const val STK_PROCESS = "com.android.phone"
    const val SUPPORTED_STK_VERSION = 15_001_030L
    const val SUPPORTED_STK_VERSION_NAME = "15.1.30"
    const val SUPPORTED_DEVICE = "PJD110"
    const val CONFIGURED_SLOT_ID = 0

    fun isVerified(deviceModel: String, stkPackage: String, stkVersion: Long, slotId: Int): Boolean =
        deviceModel == SUPPORTED_DEVICE &&
            stkPackage == STK_PACKAGE &&
            stkVersion == SUPPORTED_STK_VERSION &&
            slotId == CONFIGURED_SLOT_ID
}
