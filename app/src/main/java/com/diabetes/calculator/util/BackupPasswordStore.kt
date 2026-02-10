package com.diabetes.calculator.util

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

class BackupPasswordStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOrCreatePassword(): String {
        val existing = prefs.getString(KEY_PASSWORD, null)
        if (!existing.isNullOrBlank()) return existing

        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val password = Base64.encodeToString(randomBytes, Base64.NO_WRAP)

        prefs.edit().putString(KEY_PASSWORD, password).apply()
        return password
    }

    companion object {
        private const val PREFS_NAME = "backup_secrets"
        private const val KEY_PASSWORD = "auto_backup_password"
    }
}
