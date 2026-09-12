package io.github.tacpr.simfingerprint.auth

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import io.github.tacpr.simfingerprint.AuthContract
import io.github.tacpr.simfingerprint.CompatibilityProfile
import io.github.tacpr.simfingerprint.data.AppPreferences
import io.github.tacpr.simfingerprint.data.DiagnosticsStore
import io.github.tacpr.simfingerprint.security.SecretStore

class AuthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val appContext = context?.applicationContext ?: return status(AuthContract.STATUS_ERROR, "no_context")
        val rejection = verifyCaller(Binder.getCallingUid())
        if (rejection != null) {
            return status(AuthContract.STATUS_DENIED, rejection)
        }

        return when (method) {
            AuthContract.METHOD_BEGIN_AUTH -> beginAuth(extras ?: Bundle.EMPTY)
            AuthContract.METHOD_CONSUME_SECRET -> consumeSecret(extras ?: Bundle.EMPTY)
            AuthContract.METHOD_CANCEL_AUTH -> cancelAuth(extras ?: Bundle.EMPTY)
            else -> status(AuthContract.STATUS_REJECTED, "unknown_method")
        }
    }

    private fun beginAuth(extras: Bundle): Bundle {
        val appContext = requireNotNull(context).applicationContext
        val preferences = AppPreferences(appContext)
        preferences.lastHookHeartbeat = System.currentTimeMillis()
        DiagnosticsStore.record(appContext, "HOOK_READY", CompatibilityProfile.SUPPORTED_STK_VERSION_NAME)

        val eligible = extras.getBoolean(AuthContract.KEY_ELIGIBLE, false)
        val reason = extras.getString(AuthContract.KEY_REASON).orEmpty()
        if (!eligible) {
            DiagnosticsStore.record(appContext, "PROMPT_REJECTED", reason.ifBlank { "matcher" })
            return status(AuthContract.STATUS_REJECTED, reason.ifBlank { "matcher" })
        }

        val slotId = extras.getInt(AuthContract.KEY_SLOT_ID, -1)
        val reportedVersion = extras.getLong(AuthContract.KEY_STK_VERSION, -1L)
        if (slotId != preferences.configuredSlotId || reportedVersion != CompatibilityProfile.SUPPORTED_STK_VERSION) {
            DiagnosticsStore.record(appContext, "PROMPT_REJECTED", "profile_mismatch")
            return status(AuthContract.STATUS_REJECTED, "profile_mismatch")
        }
        if (!preferences.enabled || !SecretStore(appContext).hasSecret()) {
            return status(AuthContract.STATUS_NOT_CONFIGURED, "disabled_or_missing_pin")
        }

        val sessionId = AuthSessionRegistry.begin()
        val intent = Intent(appContext, BiometricGateActivity::class.java)
            .putExtra(AuthContract.KEY_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        DiagnosticsStore.record(appContext, "PROMPT_MATCHED", "slot_$slotId")
        return Bundle().apply {
            putString(AuthContract.KEY_STATUS, AuthContract.STATUS_OK)
            putString(AuthContract.KEY_SESSION_ID, sessionId)
            putParcelable(AuthContract.KEY_PENDING_INTENT, pendingIntent)
        }
    }

    private fun consumeSecret(extras: Bundle): Bundle {
        val appContext = requireNotNull(context).applicationContext
        val id = extras.getString(AuthContract.KEY_SESSION_ID).orEmpty()
        if (id.length !in 40..64) return status(AuthContract.STATUS_REJECTED, "bad_session")
        val result = AuthSessionRegistry.consume(id)
        return when (result.status) {
            AuthContract.STATUS_OK -> {
                val secret = result.secret ?: return status(AuthContract.STATUS_ERROR, "missing_secret")
                DiagnosticsStore.record(appContext, "PIN_CONSUMED")
                Bundle().apply {
                    putString(AuthContract.KEY_STATUS, AuthContract.STATUS_OK)
                    putByteArray(AuthContract.KEY_SECRET, secret)
                }
            }
            AuthContract.STATUS_PENDING -> status(AuthContract.STATUS_PENDING)
            AuthContract.STATUS_CANCELLED -> status(AuthContract.STATUS_CANCELLED)
            else -> status(AuthContract.STATUS_EXPIRED)
        }
    }

    private fun cancelAuth(extras: Bundle): Bundle {
        val appContext = requireNotNull(context).applicationContext
        val id = extras.getString(AuthContract.KEY_SESSION_ID).orEmpty()
        val outcome = extras.getString(AuthContract.KEY_OUTCOME).orEmpty()
        if (id.isNotBlank()) AuthSessionRegistry.cancel(id)
        if (outcome == "submit") {
            DiagnosticsStore.record(appContext, "STK_SUBMIT_TRIGGERED")
        }
        return status(AuthContract.STATUS_OK)
    }

    private fun verifyCaller(callingUid: Int): String? {
        val appContext = context?.applicationContext ?: return "no_context"
        val packageManager = appContext.packageManager
        return try {
            val appInfo = packageManager.getApplicationInfo(CompatibilityProfile.STK_PACKAGE, 0)
            val packageInfo = packageManager.getPackageInfo(CompatibilityProfile.STK_PACKAGE, 0)
            val facts = CallerFacts(
                callingUid = callingUid,
                stkUid = appInfo.uid,
                callingUidOwnsStk = packageManager.getPackagesForUid(callingUid)
                    ?.contains(CompatibilityProfile.STK_PACKAGE) == true,
                stkIsSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                stkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
            )
            CallerPolicy.rejectionReason(facts)
        } catch (_: Exception) {
            "stk_missing"
        }
    }

    private fun status(value: String, reason: String = ""): Bundle = Bundle().apply {
        putString(AuthContract.KEY_STATUS, value)
        if (reason.isNotBlank()) putString(AuthContract.KEY_REASON, reason)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
