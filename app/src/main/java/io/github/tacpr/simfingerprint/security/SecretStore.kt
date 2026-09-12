package io.github.tacpr.simfingerprint.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSecret(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    fun createEncryptionCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    fun createDecryptionCipher(): Cipher {
        val iv = decode(KEY_IV) ?: error("Missing IV")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(128, iv))
        iv.fill(0)
        return cipher
    }

    fun save(pin: CharArray, authenticatedCipher: Cipher) {
        require(pin.size == 6 && pin.all(Char::isDigit))
        val plain = ByteArray(pin.size) { index -> pin[index].code.toByte() }
        val encrypted = try {
            authenticatedCipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        try {
            val committed = prefs.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
                .putInt(KEY_SCHEMA, 1)
                .commit()
            check(committed) { "Encrypted PIN was not persisted" }
        } finally {
            encrypted.fill(0)
        }
    }

    fun decrypt(authenticatedCipher: Cipher): ByteArray {
        val encrypted = decode(KEY_CIPHERTEXT) ?: error("Missing encrypted PIN")
        return try {
            authenticatedCipher.doFinal(encrypted)
        } finally {
            encrypted.fill(0)
        }
    }

    fun delete() {
        prefs.edit().clear().commit()
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getExistingKey(): SecretKey {
        val key = keyStore().getKey(KEY_ALIAS, null)
        return key as? SecretKey ?: error("Biometric key is missing")
    }

    private fun getOrCreateKey(): SecretKey {
        runCatching { getExistingKey() }.getOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun decode(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    companion object {
        private const val PREFS_NAME = "secret_store"
        private const val KEY_ALIAS = "sim_fingerprint_pin_v1"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        private const val KEY_SCHEMA = "schema"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
