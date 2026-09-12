package io.github.tacpr.simfingerprint.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.tacpr.simfingerprint.BuildConfig
import io.github.tacpr.simfingerprint.CompatibilityProfile
import io.github.tacpr.simfingerprint.R
import io.github.tacpr.simfingerprint.data.AppPreferences
import io.github.tacpr.simfingerprint.data.DiagnosticsStore
import io.github.tacpr.simfingerprint.security.SecretStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ContextCompat.getColor(this, R.color.page_background)
        if (!AppPreferences(this).termsAccepted) showTermsDialog(initial = true)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val preferences = AppPreferences(this)
        val secretStore = SecretStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(36))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.page_background))
        }
        root.addView(text("SIM 指纹登录", 28f, bold = true))
        root.addSpaced(
            text(
                "指纹仅用于解锁本机加密保存的 SIM PIN，最终认证仍由 SIM 卡工具包完成。",
                14f,
                R.color.text_secondary,
            ),
            8,
        )
        root.addSpaced(statusCard(preferences, secretStore), 24)
        root.addSpaced(controlCard(preferences, secretStore), 16)
        root.addSpaced(compatibilityCard(preferences), 16)
        root.addSpaced(safetyCard(), 16)
        root.addSpaced(diagnosticsCard(), 16)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun statusCard(preferences: AppPreferences, secretStore: SecretStore) = card(
        sectionLayout(this).apply {
            addView(text("运行状态", 19f, bold = true))
            val stk = inspectStk()
            addSpaced(statusLine("适配设备", "${Build.MODEL} / Android ${Build.VERSION.RELEASE}", Build.MODEL == CompatibilityProfile.SUPPORTED_DEVICE))
            addSpaced(statusLine("STK 工具包", stk.description, stk.compatible), 8)
            val biometric = BiometricManager.from(this@MainActivity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            addSpaced(statusLine("强指纹", biometricText(biometric), biometric == BiometricManager.BIOMETRIC_SUCCESS), 8)
            addSpaced(statusLine("PIN 密文", if (secretStore.hasSecret()) "已安全保存" else "尚未录入", secretStore.hasSecret()), 8)
            val storageWritable = runCatching { preferences.storageIsWritable() }.getOrDefault(false)
            addSpaced(statusLine("配置存储", if (storageWritable) "可持久化" else "写入失败", storageWritable), 8)
            val heartbeat = preferences.lastHookHeartbeat
            val heartbeatText = if (heartbeat == 0L) "等待首次登录弹窗" else formatTime(heartbeat)
            addSpaced(statusLine("Hook 心跳", heartbeatText, heartbeat > 0L), 8)
            addSpaced(
                MaterialButton(this@MainActivity).apply {
                    text = "打开 LSPosed 检查作用域"
                    setOnClickListener { openLsposedScope() }
                },
                16,
            )
            addSpaced(text("本机作用域只选择：电话服务（com.android.phone）。模块在该进程内仍只处理 SIM 卡工具包界面；指纹页由前台 STK 按需启动，不要求模块应用常驻后台。", 13f, R.color.text_secondary), 10)
        },
    )

    private fun controlCard(preferences: AppPreferences, secretStore: SecretStore) = card(
        sectionLayout(this).apply {
            addView(text("认证设置", 19f, bold = true))
            addSpaced(
                MaterialCheckBox(this@MainActivity).apply {
                    text = "启用指纹自动登录"
                    isChecked = preferences.enabled && secretStore.hasSecret()
                    setOnCheckedChangeListener { button, checked ->
                        if (checked && !secretStore.hasSecret()) {
                            button.isChecked = false
                            Toast.makeText(context, "请先录入 SIM PIN", Toast.LENGTH_SHORT).show()
                        } else {
                            preferences.enabled = checked
                        }
                    }
                },
                14,
            )
            addSpaced(
                MaterialButton(this@MainActivity).apply {
                    text = if (secretStore.hasSecret()) "重新录入 SIM PIN" else "录入 SIM PIN"
                    setOnClickListener { startActivity(Intent(this@MainActivity, PinEnrollmentActivity::class.java)) }
                },
                12,
            )
            if (secretStore.hasSecret()) {
                addSpaced(
                    MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "删除已保存的 PIN"
                        setOnClickListener { confirmDeletePin() }
                    },
                    8,
                )
            }
            addSpaced(text("每个登录请求只自动提交一次，提交后 60 秒内不会再次自动尝试。", 13f, R.color.text_secondary), 12)
        },
    )

    private fun compatibilityCard(preferences: AppPreferences) = card(sectionLayout(this).apply {
        addView(text("双卡与设备适配", 19f, bold = true))
        val group = RadioGroup(this@MainActivity)
        val slot1 = RadioButton(this@MainActivity).apply { text = "SIM 卡 1（主卡，已验证）"; isChecked = preferences.configuredSlotId == 0 }
        val slot2 = RadioButton(this@MainActivity).apply { text = "SIM 卡 2（未验证，暂不自动提交）"; isEnabled = false }
        slot1.setOnClickListener { preferences.configuredSlotId = 0; render() }
        group.addView(slot1); group.addView(slot2); addView(group)
            addSpaced(text("当前只验证 PJD110 + STK 15.1.30。其他品牌和双卡需真机回归后才会开放；未知设备不会自动提交。", 13f, R.color.text_secondary), 10)
    })

    private fun showTermsDialog(initial: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle("用户协议与风险提示")
            .setMessage("本模块仅供本人在本人设备上辅助 SIM 认证，不绕过 SIM 卡、运营商或应用的认证。\n\n您应确认已获得必要授权，自行承担使用本模块及保存 SIM PIN 带来的账号、资金、隐私和合规风险。\n\nPIN 仅以 Android Keystore 密文保存，但指纹、系统、LSPosed、STK 或其他模块异常仍可导致失败。本模块不提供运营商、手机厂商或应用方的官方保证，不保证兼容性、可用性或业务结果。模块不申请网络权限，不上传认证数据。协议不排除法律不允许通过约定排除的责任。")
            .setNegativeButton(if (initial) "退出" else "关闭") { _, _ -> if (initial) finish() }
            .setPositiveButton("同意并继续") { _, _ -> AppPreferences(this).termsAccepted = true }
            .setCancelable(false)
            .show()
    }

    private fun safetyCard() = card(
        sectionLayout(this).apply {
            addView(text("安全边界", 19f, bold = true))
            addSpaced(text("卡一的 STK 原生密码输入页会直接弹出指纹，不限制提示文案。请只在您刚主动发起操作时通过指纹。", 14f, R.color.text_secondary), 12)
            addSpaced(text("系统或 STK 版本变化时自动停用兼容配置，不会尝试猜测字段或提交认证。", 14f, R.color.text_secondary), 10)
            addSpaced(
                MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "查看用户协议与风险提示"
                    setOnClickListener { showTermsDialog(initial = false) }
                },
                10,
            )
        },
    )

    private fun diagnosticsCard() = card(
        sectionLayout(this).apply {
            addView(text("脱敏诊断", 19f, bold = true))
            val lines = DiagnosticsStore.read(this@MainActivity)
            if (lines.isEmpty()) {
                addSpaced(text("暂无记录。触发一次 SIM 登录后会显示 Hook 与指纹状态。", 13f, R.color.text_secondary), 10)
            } else {
                lines.take(12).forEach { line ->
                    val parts = line.split('|', limit = 3)
                    val display = if (parts.size == 3) "${formatTime(parts[0].toLongOrNull() ?: 0L)}  ${parts[1]}  ${parts[2]}" else line
                    addSpaced(text(display, 12f, R.color.text_secondary), 7)
                }
                addSpaced(
                    MaterialButton(this@MainActivity).apply {
                        text = "清空诊断"
                        setOnClickListener {
                            DiagnosticsStore.clear(this@MainActivity)
                            render()
                        }
                    },
                    10,
                )
            }
        },
    )

    private fun statusLine(label: String, value: String, ok: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(label, 14f, R.color.text_secondary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 14f, if (ok) R.color.status_ok else R.color.status_warn, bold = true))
    }

    private fun confirmDeletePin() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除 SIM PIN？")
            .setMessage("删除后，STK 登录弹窗将恢复为完全手动输入。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                SecretStore(this).delete()
                AppPreferences(this).enabled = false
                render()
            }
            .show()
    }

    private fun openLsposedScope() {
        val direct = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                "org.lsposed.manager",
                "org.lsposed.manager.ui.activity.MainActivity",
            )
            data = Uri.parse("module://${BuildConfig.APPLICATION_ID}:0")
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            val fallback = packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            if (fallback == null) {
                Toast.makeText(this, "未找到 LSPosed 管理器", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(fallback)
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, "LSPosed 管理器拒绝外部打开，请从桌面进入", Toast.LENGTH_LONG).show()
        }
    }

    private fun inspectStk(): StkStatus = try {
        val appInfo = packageManager.getApplicationInfo(CompatibilityProfile.STK_PACKAGE, 0)
        val packageInfo = packageManager.getPackageInfo(CompatibilityProfile.STK_PACKAGE, 0)
        val version = packageInfo.longVersionCode
        val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        StkStatus(
            compatible = system && version == CompatibilityProfile.SUPPORTED_STK_VERSION,
            description = "${packageInfo.versionName ?: version} / ${if (system) "系统应用" else "非系统应用"}",
        )
    } catch (_: Exception) {
        StkStatus(false, "未找到")
    }

    private fun biometricText(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "可用"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "未录入指纹"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "无硬件"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "暂不可用"
        else -> "错误 $code"
    }

    private fun formatTime(timestamp: Long): String =
        if (timestamp <= 0L) "未知" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))

    private data class StkStatus(val compatible: Boolean, val description: String)
}
