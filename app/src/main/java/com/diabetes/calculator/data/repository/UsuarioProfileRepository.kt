package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.UsuarioProfileDao
import com.diabetes.calculator.data.entity.UsuarioProfile
import com.diabetes.calculator.util.LibreviewSecretStore
import com.diabetes.calculator.util.NightscoutTokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repositorio para la gestión del perfil de usuario y configuración.
 * Abstrae el acceso a datos del DAO.
 */
class UsuarioProfileRepository(
    private val dao: UsuarioProfileDao,
    private val tokenStore: NightscoutTokenStore,
    private val libreviewSecretStore: LibreviewSecretStore? = null
) {

    /**
     * Obtiene el perfil del usuario de forma reactiva.
     */
    val profile: Flow<UsuarioProfile?> = dao.getProfile().map { profile ->
        if (profile == null) return@map null
        val storedToken = tokenStore.getToken()
        val token = storedToken ?: profile.nightscoutToken
        if (storedToken == null && !profile.nightscoutToken.isNullOrBlank()) {
            tokenStore.setToken(profile.nightscoutToken)
        }
        profile.copy(nightscoutToken = token)
    }

    /**
     * Obtiene el perfil actual de forma síncrona.
     */
    suspend fun getProfileSync(): UsuarioProfile? {
        val profile = dao.getProfileSync() ?: return null
        val storedToken = tokenStore.getToken()
        val token = storedToken ?: profile.nightscoutToken
        if (storedToken == null && !profile.nightscoutToken.isNullOrBlank()) {
            tokenStore.setToken(profile.nightscoutToken)
        }
        return profile.copy(nightscoutToken = token)
    }

    /**
     * Inserta o reemplaza el perfil del usuario.
     */
    suspend fun insertProfile(profile: UsuarioProfile) {
        tokenStore.setToken(profile.nightscoutToken)
        dao.insertProfile(profile.copy(nightscoutToken = null))
    }

    /**
     * Actualiza el perfil del usuario.
     */
    suspend fun update(profile: UsuarioProfile) {
        tokenStore.setToken(profile.nightscoutToken)
        dao.update(profile.copy(nightscoutToken = null))
    }

    suspend fun updateNightscoutBackfillDoneAt(profileId: Int, timestamp: Long?) {
        dao.updateNightscoutBackfillDoneAt(profileId, timestamp)
    }

    suspend fun updateLibreviewBackfillDoneAt(profileId: Int, timestamp: Long?) {
        dao.updateLibreviewBackfillDoneAt(profileId, timestamp)
    }

    fun getLibreviewEmail(): String? = libreviewSecretStore?.getEmail()

    fun getLibreviewPassword(): String? = libreviewSecretStore?.getPassword()

    fun setLibreviewCredentials(email: String?, password: String?) {
        libreviewSecretStore?.setCredentials(email, password)
    }

    fun clearLibreviewSessionSecrets() {
        libreviewSecretStore?.clearSession()
    }

    /**
     * Elimina el perfil del usuario.
     */
    suspend fun deleteAll() {
        tokenStore.setToken(null)
        libreviewSecretStore?.clearAll()
        dao.deleteAll()
    }

    /**
     * Migra el token desde la base de datos a almacenamiento cifrado si existe.
     */
    suspend fun migrateTokenIfNeeded() {
        val profile = dao.getProfileSync() ?: return
        if (!profile.nightscoutToken.isNullOrBlank()) {
            tokenStore.setToken(profile.nightscoutToken)
            dao.update(profile.copy(nightscoutToken = null))
        }
    }
}
