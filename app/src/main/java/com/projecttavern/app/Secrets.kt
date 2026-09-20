package com.projecttavern.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Secrets {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "project_tavern_aes"
    private const val PREF = "tavern_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun save(ctx: Context, profileId: String, raw: String) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (raw.isBlank()) {
            prefs.edit().remove(profileId).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val enc = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
            prefs.edit().putString(profileId, packed).apply()
        } catch (_: Exception) {
            prefs.edit().putString("plain_$profileId", raw).apply()
        }
    }

    fun get(ctx: Context, profileId: String): String {
        val prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val packed = prefs.getString(profileId, null)
        if (!packed.isNullOrBlank()) {
            try {
                val parts = packed.split(":", limit = 2)
                if (parts.size == 2) {
                    val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                    val enc = Base64.decode(parts[1], Base64.NO_WRAP)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                    return String(cipher.doFinal(enc), Charsets.UTF_8)
                }
            } catch (_: Exception) {}
        }
        return prefs.getString("plain_$profileId", "").orEmpty()
    }

    fun remove(ctx: Context, profileId: String) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(profileId).remove("plain_$profileId").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
