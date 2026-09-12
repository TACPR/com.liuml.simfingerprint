package io.github.tacpr.simfingerprint

import android.net.Uri

object AuthContract {
    const val AUTHORITY = "io.github.tacpr.simfingerprint.auth"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    const val METHOD_BEGIN_AUTH = "begin_auth"
    const val METHOD_CONSUME_SECRET = "consume_secret"
    const val METHOD_CANCEL_AUTH = "cancel_auth"

    const val KEY_STATUS = "status"
    const val KEY_REASON = "reason"
    const val KEY_ELIGIBLE = "eligible"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_PENDING_INTENT = "pending_intent"
    const val KEY_SECRET = "secret"
    const val KEY_SLOT_ID = "slot_id"
    const val KEY_STK_VERSION = "stk_version"
    const val KEY_OUTCOME = "outcome"
    const val KEY_RESULT_RECEIVER = "result_receiver"

    const val STATUS_OK = "ok"
    const val STATUS_PENDING = "pending"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_EXPIRED = "expired"
    const val STATUS_REJECTED = "rejected"
    const val STATUS_DENIED = "denied"
    const val STATUS_NOT_CONFIGURED = "not_configured"
    const val STATUS_ERROR = "error"

    const val MODULE_PACKAGE = "io.github.tacpr.simfingerprint"
    const val BIOMETRIC_ACTIVITY = "$MODULE_PACKAGE.auth.BiometricGateActivity"
}
