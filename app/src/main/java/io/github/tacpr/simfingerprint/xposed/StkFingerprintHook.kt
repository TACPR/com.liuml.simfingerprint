package io.github.tacpr.simfingerprint.xposed

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.tacpr.simfingerprint.AuthContract
import io.github.tacpr.simfingerprint.CompatibilityProfile
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

class StkFingerprintHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != CompatibilityProfile.STK_PACKAGE &&
            lpparam.packageName != CompatibilityProfile.PHONE_PACKAGE
        ) {
            return
        }
        XposedBridge.log(
            "$LOG_PREFIX package callback package=${lpparam.packageName} process=${lpparam.processName}",
        )
        synchronized(hookedLoaders) {
            if (hookedLoaders.containsKey(lpparam.classLoader)) return
            try {
                Class.forName(CompatibilityProfile.STK_ACTIVITY, false, lpparam.classLoader)
            } catch (throwable: Throwable) {
                XposedBridge.log(
                    "$LOG_PREFIX STK class unavailable package=${lpparam.packageName} " +
                        "process=${lpparam.processName} reason=${throwable.javaClass.simpleName}",
                )
                return
            }
            hookActivity(lpparam.classLoader)
            hookedLoaders[lpparam.classLoader] = true
            XposedBridge.log("$LOG_PREFIX hook installed package=${lpparam.packageName} process=${lpparam.processName}")
        }
    }

    private fun hookActivity(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            CompatibilityProfile.STK_ACTIVITY,
            classLoader,
            "onPostCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    schedule(param.thisObject as? Activity, 300L)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            CompatibilityProfile.STK_ACTIVITY,
            classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    schedule(param.thisObject as? Activity, 220L)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            CompatibilityProfile.STK_ACTIVITY,
            classLoader,
            "onDestroy",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val state = synchronized(states) { states.remove(activity) }
                    state?.pendingSecret?.fill(0)
                    state?.pendingSecret = null
                    state?.receiver = null
                }
            },
        )
    }

    private fun schedule(activity: Activity?, delayMillis: Long) {
        if (activity == null || activity.javaClass.name != CompatibilityProfile.STK_ACTIVITY) return
        activity.window?.decorView?.postDelayed({ drive(activity) }, delayMillis)
    }

    private fun drive(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val state = synchronized(states) { states.getOrPut(activity) { HookState() } }
        if (state.terminal || state.submitted) return

        if (state.pendingSecret != null) {
            consumeDirectAndSubmit(activity, state)
            return
        }
        if (state.sessionId != null) return
        if (state.requested) return
        if (!activity.hasWindowFocus()) {
            if (state.focusRetries++ < 8) schedule(activity, 150L)
            return
        }

        val facts = captureFacts(activity)
        val decision = PromptMatcher.evaluate(facts)
        if (!decision.eligible) {
            XposedBridge.log(
                "$LOG_PREFIX prompt rejected reason=${decision.reason} slot=${facts.slotId} " +
                    "version=${facts.stkVersion}",
            )
            state.terminal = true
            return
        }
        if (SystemClock.elapsedRealtime() - lastSubmitElapsed.get() < SUBMIT_COOLDOWN_MS) {
            XposedBridge.log("$LOG_PREFIX prompt rejected reason=submit_cooldown slot=${facts.slotId}")
            state.terminal = true
            return
        }
        launchDirectAuth(activity, state, facts)
    }

    private fun launchDirectAuth(activity: Activity, state: HookState, facts: PromptFacts) {
        val sessionBytes = ByteArray(32).also(secureRandom::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(sessionBytes)
        sessionBytes.fill(0)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                onDirectResult(activity, state, id, resultCode, resultData)
            }
        }
        state.requested = true
        state.sessionId = id
        state.receiver = receiver
        val intent = Intent()
            .setClassName(AuthContract.MODULE_PACKAGE, AuthContract.BIOMETRIC_ACTIVITY)
            .putExtra(AuthContract.KEY_SESSION_ID, id)
            .putExtra(AuthContract.KEY_SLOT_ID, facts.slotId)
            .putExtra(AuthContract.KEY_STK_VERSION, facts.stkVersion)
            .putExtra(AuthContract.KEY_RESULT_RECEIVER, receiver)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            XposedBridge.log("$LOG_PREFIX biometric direct launch slot=${facts.slotId}")
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, DIRECT_AUTH_REQUEST_CODE)
        } catch (throwable: Throwable) {
            state.sessionId = null
            state.receiver = null
            state.terminal = true
            XposedBridge.log("$LOG_PREFIX direct launch failed reason=${throwable.javaClass.simpleName}")
        }
    }

    private fun onDirectResult(
        activity: Activity,
        state: HookState,
        expectedId: String,
        resultCode: Int,
        data: Bundle?,
    ) {
        val secret = data?.getByteArray(AuthContract.KEY_SECRET)
        val valid = !activity.isFinishing && !activity.isDestroyed &&
            state.sessionId == expectedId &&
            data?.getString(AuthContract.KEY_SESSION_ID) == expectedId &&
            resultCode == Activity.RESULT_OK &&
            data?.getString(AuthContract.KEY_STATUS) == AuthContract.STATUS_OK &&
            secret?.size == 6 && secret.all { it.toInt() in 48..57 }
        state.sessionId = null
        state.receiver = null
        if (!valid) {
            secret?.fill(0)
            state.terminal = true
            XposedBridge.log("$LOG_PREFIX biometric direct result cancelled")
            return
        }
        state.pendingSecret?.fill(0)
        state.pendingSecret = secret
        XposedBridge.log("$LOG_PREFIX biometric direct result received")
        schedule(activity, 50L)
    }

    private fun consumeDirectAndSubmit(activity: Activity, state: HookState) {
        if (!activity.hasWindowFocus()) return
        val secret = state.pendingSecret ?: return
        state.pendingSecret = null
        try {
            val editText = getField(activity, "mTextIn") as? EditText ?: return
            val button = getField(activity, "mOkBtn") as? Button ?: return
            if (editText.text?.isNotEmpty() == true) {
                state.terminal = true
                return
            }
            editText.setText(String(secret, StandardCharsets.US_ASCII))
            editText.setSelection(editText.text?.length ?: 0)
            submitWhenReady(activity, button, state, 0)
        } finally {
            secret.fill(0)
        }
    }

    private fun submitWhenReady(
        activity: Activity,
        button: Button,
        state: HookState,
        attempt: Int,
    ) {
        button.postDelayed({
            if (activity.isFinishing || activity.isDestroyed || state.submitted) return@postDelayed
            if (button.isEnabled && activity.hasWindowFocus()) {
                state.submitted = true
                state.terminal = true
                lastSubmitElapsed.set(SystemClock.elapsedRealtime())
                XposedBridge.log("$LOG_PREFIX native submit triggered")
                button.performClick()
            } else if (attempt < 5) {
                submitWhenReady(activity, button, state, attempt + 1)
            } else {
                state.terminal = true
            }
        }, 100L)
    }

    private fun captureFacts(activity: Activity): PromptFacts {
        val promptView = getField(activity, "mPromptView") as? TextView
        val input = getField(activity, "mTextIn") as? EditText
        val button = getField(activity, "mOkBtn") as? Button
        val stkInput = getField(activity, "mStkInput")
        val slotId = (getField(activity, "mSlotId") as? Int) ?: -1
        val minLength = (stkInput?.let { getField(it, "minLen") } as? Int) ?: -1
        val maxLength = (stkInput?.let { getField(it, "maxLen") } as? Int) ?: -1
        val password = input?.let(::isPasswordInput) == true
        val version = runCatching {
            activity.packageManager.getPackageInfo(CompatibilityProfile.STK_PACKAGE, 0).longVersionCode
        }.getOrDefault(-1L)
        return PromptFacts(
            activityClass = activity.javaClass.name,
            prompt = promptView?.text?.toString().orEmpty(),
            slotId = slotId,
            deviceModel = Build.MODEL,
            isPasswordInput = password,
            inputIsEmpty = input?.text?.isEmpty() == true,
            minLength = minLength,
            maxLength = maxLength,
            hasWindowFocus = activity.hasWindowFocus(),
            stkVersion = version,
            fieldsCompatible = promptView != null && input != null && button != null && stkInput != null,
        )
    }

    private fun isPasswordInput(input: EditText): Boolean {
        if (input.transformationMethod is PasswordTransformationMethod) return true
        val variation = input.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun getField(instance: Any, name: String): Any? = runCatching {
        findField(instance.javaClass, name).get(instance)
    }.getOrNull()

    private fun findField(start: Class<*>, name: String): Field {
        var current: Class<*>? = start
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        throw NoSuchFieldException(name)
    }

    private data class HookState(
        var requested: Boolean = false,
        var terminal: Boolean = false,
        var submitted: Boolean = false,
        var sessionId: String? = null,
        var focusRetries: Int = 0,
        var pendingSecret: ByteArray? = null,
        var receiver: ResultReceiver? = null,
    )

    companion object {
        private const val LOG_PREFIX = "SimFingerprint"
        private const val SUBMIT_COOLDOWN_MS = 60_000L
        private const val DIRECT_AUTH_REQUEST_CODE = 0x5349
        private val lastSubmitElapsed = AtomicLong(0L)
        private val secureRandom = SecureRandom()
        private val states = Collections.synchronizedMap(WeakHashMap<Activity, HookState>())
        private val hookedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
    }
}
