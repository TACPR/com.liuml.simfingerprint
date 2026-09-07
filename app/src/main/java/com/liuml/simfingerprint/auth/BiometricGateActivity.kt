package com.liuml.simfingerprint.auth

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.liuml.simfingerprint.AuthContract
import com.liuml.simfingerprint.CompatibilityProfile
import com.liuml.simfingerprint.R
import com.liuml.simfingerprint.data.AppPreferences
import com.liuml.simfingerprint.data.DiagnosticsStore
import com.liuml.simfingerprint.security.SecretStore

class BiometricGateActivity : FragmentActivity() {
    private lateinit var sessionId: String
    private var directReceiver: ResultReceiver? = null
    private var directMode = false
    private var terminal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sessionId = intent.getStringExtra(AuthContract.KEY_SESSION_ID).orEmpty()
        @Suppress("DEPRECATION")
        directReceiver = intent.getParcelableExtra(AuthContract.KEY_RESULT_RECEIVER)
        directMode = directReceiver != null
        val invalidSession = sessionId.length !in 40..64 || if (directMode) {
            directRequestRejection() != null
        } else {
            !AuthSessionRegistry.isPending(sessionId)
        }
        if (invalidSession) {
            finishSafely("invalid_session")
            return
        }

        val preferences = AppPreferences(applicationContext)
        val secretStore = SecretStore(applicationContext)
        if (!preferences.enabled || !secretStore.hasSecret()) {
            finishSafely("disabled_or_missing_pin")
            return
        }
        preferences.lastHookHeartbeat = System.currentTimeMillis()
        DiagnosticsStore.record(applicationContext, "HOOK_READY", "direct_activity")

        val cipher = try {
            secretStore.createDecryptionCipher()
        } catch (_: Exception) {
            secretStore.delete()
            preferences.enabled = false
            DiagnosticsStore.record(applicationContext, "BIOMETRIC_CANCELLED", "key_invalid")
            Toast.makeText(this, "指纹密钥已失效，请重新录入 SIM PIN", Toast.LENGTH_LONG).show()
            finishSafely("key_invalid")
            return
        }

        DiagnosticsStore.record(applicationContext, "BIOMETRIC_STARTED")
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        finishSafely("missing_crypto")
                        return
                    }
                    val secret = try {
                        secretStore.decrypt(authenticatedCipher)
                    } catch (_: Exception) {
                        finishSafely("decrypt_failed")
                        return
                    }
                    val valid = secret.size == 6 && secret.all { it in '0'.code.toByte()..'9'.code.toByte() }
                    if (!valid) {
                        secret.fill(0)
                        finishSafely("invalid_secret")
                        return
                    }
                    if (directMode) {
                        terminal = true
                        sendDirectResult(Activity.RESULT_OK, AuthContract.STATUS_OK, secret = secret)
                        secret.fill(0)
                        DiagnosticsStore.record(applicationContext, "PIN_CONSUMED", "direct_binder")
                    } else if (!AuthSessionRegistry.markAuthenticated(sessionId, secret)) {
                        secret.fill(0)
                        finishSafely("invalid_session")
                        return
                    } else {
                        terminal = true
                    }
                    DiagnosticsStore.record(applicationContext, "BIOMETRIC_SUCCESS")
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finishSafely("error_$errorCode")
                }

                override fun onAuthenticationFailed() {
                    DiagnosticsStore.record(applicationContext, "BIOMETRIC_FAILED")
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun finishSafely(reason: String) {
        if (terminal) return
        terminal = true
        if (directMode) {
            sendDirectResult(Activity.RESULT_CANCELED, AuthContract.STATUS_CANCELLED, reason)
        } else if (::sessionId.isInitialized && sessionId.isNotBlank()) {
            AuthSessionRegistry.cancel(sessionId)
        }
        DiagnosticsStore.record(applicationContext, "BIOMETRIC_CANCELLED", reason)
        finish()
    }

    private fun directRequestRejection(): String? {
        val caller = callingActivity ?: return "missing_caller"
        if (caller.packageName != CompatibilityProfile.STK_PACKAGE ||
            caller.className != CompatibilityProfile.STK_ACTIVITY
        ) {
            return "wrong_caller"
        }
        if (!CompatibilityProfile.isVerified(
                Build.MODEL,
                CompatibilityProfile.STK_PACKAGE,
                intent.getLongExtra(AuthContract.KEY_STK_VERSION, -1L),
                intent.getIntExtra(AuthContract.KEY_SLOT_ID, -1),
            )
        ) {
            return "profile_mismatch"
        }
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(CompatibilityProfile.STK_PACKAGE, 0)
            val version = packageManager.getPackageInfo(CompatibilityProfile.STK_PACKAGE, 0).longVersionCode
            when {
                appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 -> "stk_not_system"
                version != CompatibilityProfile.SUPPORTED_STK_VERSION -> "unsupported_version"
                else -> null
            }
        }.getOrElse { "stk_missing" }
    }

    private fun sendDirectResult(
        resultCode: Int,
        status: String,
        reason: String = "",
        secret: ByteArray? = null,
    ) {
        val outbound = secret?.copyOf()
        try {
            directReceiver?.send(
                resultCode,
                Bundle().apply {
                    putString(AuthContract.KEY_STATUS, status)
                    putString(AuthContract.KEY_SESSION_ID, sessionId)
                    if (reason.isNotBlank()) putString(AuthContract.KEY_REASON, reason)
                    if (outbound != null) putByteArray(AuthContract.KEY_SECRET, outbound)
                },
            )
        } finally {
            outbound?.fill(0)
        }
    }
}
