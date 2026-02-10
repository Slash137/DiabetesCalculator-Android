package com.diabetes.calculator.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class NightscoutTokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "nightscout_secrets"
        private const val KEY_TOKEN = "nightscout_token"
    }
}
