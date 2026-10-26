package com.googletv.screensaver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.googletv.screensaver.playback.SmbAuthMode
import com.googletv.screensaver.playback.SmbAuthentication
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the registered SMB password encrypted with a device-local Android Keystore key. */
class SmbCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("smb_auth", Context.MODE_PRIVATE)

    fun authentication(): SmbAuthentication = SmbAuthentication(
        mode = runCatching { SmbAuthMode.valueOf(preferences.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(SmbAuthMode.GUEST),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        password = runCatching { decrypt(preferences.getString(KEY_PASSWORD, null)) }.getOrDefault(""),
    )

    fun save(authentication: SmbAuthentication) {
        val editor = preferences.edit()
            .putString(KEY_MODE, authentication.mode.name)
            .putString(KEY_USERNAME, authentication.username.trim())
        if (authentication.password.isEmpty()) editor.remove(KEY_PASSWORD)
        else editor.putString(KEY_PASSWORD, encrypt(authentication.password))
        editor.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String {
        if (encoded.isNullOrBlank()) return ""
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "Stored credential is invalid." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "screensaver.smb.credentials"
        const val KEY_MODE = "mode"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password.gcm"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
