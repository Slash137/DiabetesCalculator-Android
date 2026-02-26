package com.diabetes.calculator.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class LibreviewSessionSecrets(
    val email: String?,
    val password: String?,
    val deviceId: String?,
    val appStableSerial: String?,
    val userToken: String?,
    val accountId: String?,
    val countryCode: String?,
    val baseUrl: String?,
    val apiKey: String?,
    val lastAuthAt: Long?,
    val lastSyncAt: Long?
)

class LibreviewSecretStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun setEmail(value: String?) {
        putOrRemove(KEY_EMAIL, value)
    }

    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun setPassword(value: String?) {
        putOrRemove(KEY_PASSWORD, value)
    }

    fun setCredentials(email: String?, password: String?) {
        prefs.edit().apply {
            if (email.isNullOrBlank()) remove(KEY_EMAIL) else putString(KEY_EMAIL, email)
            if (password.isNullOrBlank()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, password)
        }.commit()
    }

    fun getUserToken(): String? = prefs.getString(KEY_USER_TOKEN, null)

    fun setUserToken(value: String?) {
        putOrRemove(KEY_USER_TOKEN, value)
    }

    fun getAccountId(): String? = prefs.getString(KEY_ACCOUNT_ID, null)

    fun setAccountId(value: String?) {
        putOrRemove(KEY_ACCOUNT_ID, value)
    }

    fun getCountryCode(): String? = prefs.getString(KEY_COUNTRY_CODE, null)

    fun setCountryCode(value: String?) {
        putOrRemove(KEY_COUNTRY_CODE, value?.trim()?.uppercase())
    }

    fun getBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

    fun setBaseUrl(value: String?) {
        putOrRemove(KEY_BASE_URL, value)
    }

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun setApiKey(value: String?) {
        putOrRemove(KEY_API_KEY, value)
    }

    fun getLastAuthAt(): Long? {
        return if (prefs.contains(KEY_LAST_AUTH_AT)) {
            prefs.getLong(KEY_LAST_AUTH_AT, 0L)
        } else {
            null
        }
    }

    fun setLastAuthAt(value: Long?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_LAST_AUTH_AT) else putLong(KEY_LAST_AUTH_AT, value)
        }.apply()
    }

    fun getLastSyncAt(): Long? {
        return if (prefs.contains(KEY_LAST_SYNC_AT)) {
            prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        } else {
            null
        }
    }

    fun setLastSyncAt(value: Long?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_LAST_SYNC_AT) else putLong(KEY_LAST_SYNC_AT, value)
        }.apply()
    }

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).commit()
        return created
    }

    fun regenerateDeviceId(): String {
        val created = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_DEVICE_ID, created)
            .putString(KEY_APP_STABLE_SERIAL, deriveAppStableSerial(created))
            .commit()
        return created
    }

    fun getAppStableSerial(): String? = prefs.getString(KEY_APP_STABLE_SERIAL, null)

    fun setAppStableSerial(value: String?) {
        putOrRemove(KEY_APP_STABLE_SERIAL, value)
    }

    fun getOrCreateAppStableSerial(): String {
        val existing = getAppStableSerial()?.trim()
        if (!existing.isNullOrBlank()) return existing
        val serial = deriveAppStableSerial(getOrCreateDeviceId())
        prefs.edit().putString(KEY_APP_STABLE_SERIAL, serial).commit()
        return serial
    }

    fun clearSession() {
        prefs.edit().apply {
            remove(KEY_USER_TOKEN)
            remove(KEY_ACCOUNT_ID)
            remove(KEY_COUNTRY_CODE)
            remove(KEY_BASE_URL)
            remove(KEY_API_KEY)
            remove(KEY_LAST_AUTH_AT)
            remove(KEY_LAST_SYNC_AT)
        }.apply()
    }

    fun resetDeviceIdentity() {
        prefs.edit().apply {
            remove(KEY_DEVICE_ID)
            remove(KEY_APP_STABLE_SERIAL)
            remove(KEY_USER_TOKEN)
            remove(KEY_ACCOUNT_ID)
            remove(KEY_COUNTRY_CODE)
            remove(KEY_BASE_URL)
            remove(KEY_API_KEY)
            remove(KEY_LAST_AUTH_AT)
            remove(KEY_LAST_SYNC_AT)
        }.commit()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun getSessionSecrets(): LibreviewSessionSecrets {
        return LibreviewSessionSecrets(
            email = getEmail(),
            password = getPassword(),
            deviceId = prefs.getString(KEY_DEVICE_ID, null),
            appStableSerial = getAppStableSerial(),
            userToken = getUserToken(),
            accountId = getAccountId(),
            countryCode = getCountryCode(),
            baseUrl = getBaseUrl(),
            apiKey = getApiKey(),
            lastAuthAt = getLastAuthAt(),
            lastSyncAt = getLastSyncAt()
        )
    }

    private fun putOrRemove(key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        }.apply()
    }

    private fun deriveAppStableSerial(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.trim().toByteArray(StandardCharsets.UTF_8))
        val short = digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "APP-$short"
    }

    companion object {
        private const val PREFS_NAME = "libreview_secrets"
        private const val KEY_EMAIL = "libreview_email"
        private const val KEY_PASSWORD = "libreview_password"
        private const val KEY_DEVICE_ID = "libreview_device_id"
        private const val KEY_APP_STABLE_SERIAL = "libreview_app_stable_serial"
        private const val KEY_USER_TOKEN = "libreview_user_token"
        private const val KEY_ACCOUNT_ID = "libreview_account_id"
        private const val KEY_COUNTRY_CODE = "libreview_country_code"
        private const val KEY_BASE_URL = "libreview_base_url"
        private const val KEY_API_KEY = "libreview_api_key"
        private const val KEY_LAST_AUTH_AT = "libreview_last_auth_at"
        private const val KEY_LAST_SYNC_AT = "libreview_last_sync_at"
    }
}
