package io.github.tacpr.simfingerprint.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tacpr.simfingerprint.CompatibilityProfile
import io.github.tacpr.simfingerprint.R
import io.github.tacpr.simfingerprint.data.AppPreferences
import io.github.tacpr.simfingerprint.security.SecretStore
import javax.crypto.Cipher

class PinEnrollmentActivity : AppCompatActivity() {
    private lateinit var firstPin: TextInputEditText
    private lateinit var secondPin: TextInputEditText
    private var pendingPin: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = ContextCompat.getColor(this, R.color.page_background)
        setContentView(buildContent())
    }

    override fun onDestroy() {
        clearPendingPin()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(38), dp(24), dp(38))
            setBackgroundColor(ContextCompat.getColor(this@PinEnrollmentActivity, R.color.page_background))
        }
        root.addView(text("录入 SIM PIN", 27f, bold = true))
        root.addSpaced(text("PIN 将使用 Android Keystore 加密。以后每次解密都必须验证强指纹。", 14f, R.color.text_secondary), 10)
        val firstField = pinField("输入六位 SIM PIN")
        val secondField = pinField("再次输入 SIM PIN")
        firstPin = firstField.input
        secondPin = secondField.input
        root.addSpaced(firstField.layout, 28)
        root.addSpaced(secondField.layout, 14)
        root.addSpaced(
            MaterialButton(this).apply {
                text = "验证指纹并保存"
                setOnClickListener { beginEnrollment() }
            },
            22,
        )
        root.addSpaced(text("模块不会验证 PIN 是否正确。请仔细核对；错误 PIN 仍可能消耗 SIM 认证尝试次数。", 13f, R.color.status_warn), 14)
        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pinField(hint: String): PinField {
        val input = TextInputEditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 18f
        }
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        }
        return PinField(layout, input)
    }

    private fun beginEnrollment() {
        val first = editableToChars(firstPin)
        val second = editableToChars(secondPin)
        if (first.size != 6 || first.any { !it.isDigit() } || !first.contentEquals(second)) {
            first.fill('\u0000')
            second.fill('\u0000')
            Toast.makeText(this, "请输入两次完全相同的六位数字 PIN", Toast.LENGTH_SHORT).show()
            return
        }
        second.fill('\u0000')
        if (BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            first.fill('\u0000')
            Toast.makeText(this, "当前设备没有可用的强指纹", Toast.LENGTH_LONG).show()
            return
        }
        clearPendingPin()
        pendingPin = first
        val store = SecretStore(applicationContext)
        val cipher = try {
            store.createEncryptionCipher()
        } catch (_: Exception) {
            store.delete()
            runCatching { store.createEncryptionCipher() }.getOrElse {
                clearPendingPin()
                Toast.makeText(this, "无法创建指纹密钥", Toast.LENGTH_LONG).show()
                return
            }
        }
        showEnrollmentPrompt(store, cipher)
    }

    private fun showEnrollmentPrompt(store: SecretStore, cipher: Cipher) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val pin = pendingPin ?: return
                    val authenticatedCipher = result.cryptoObject?.cipher ?: run {
                        clearPendingPin()
                        return
                    }
                    try {
                        store.save(pin, authenticatedCipher)
                        AppPreferences(applicationContext).apply {
                            configuredSlotId = CompatibilityProfile.CONFIGURED_SLOT_ID
                            enabled = true
                        }
                        firstPin.text?.clear()
                        secondPin.text?.clear()
                        Toast.makeText(this@PinEnrollmentActivity, "SIM PIN 已加密保存", Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (_: Exception) {
                        Toast.makeText(this@PinEnrollmentActivity, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    } finally {
                        clearPendingPin()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    clearPendingPin()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.enroll_biometric_title))
            .setSubtitle(getString(R.string.enroll_biometric_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("取消")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun editableToChars(input: TextInputEditText): CharArray {
        val editable = input.text ?: return CharArray(0)
        return CharArray(editable.length) { index -> editable[index] }
    }

    private fun clearPendingPin() {
        pendingPin?.fill('\u0000')
        pendingPin = null
    }

    private data class PinField(
        val layout: TextInputLayout,
        val input: TextInputEditText,
    )
}
