package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.dao.UsuarioProfileDao
import com.diabetes.calculator.data.entity.UsuarioProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para el perfil de usuario.
 * Abstrae el acceso a datos del DAO.
 */
class UsuarioRepository(private val dao: UsuarioProfileDao) {
    
    /**
     * Obtiene el perfil del usuario de forma reactiva.
     */
    val profile: Flow<UsuarioProfile?> = dao.getProfile()
    
    /**
     * Obtiene el perfil de forma síncrona.
     */
    suspend fun getProfileSync(): UsuarioProfile? = dao.getProfileSync()
    
    /**
     * Guarda o actualiza el perfil del usuario.
     */
    suspend fun saveProfile(profile: UsuarioProfile) {
        val existing = dao.getProfileSync()
        if (existing != null) {
            dao.update(profile.copy(id = existing.id))
        } else {
            dao.insertProfile(profile)
        }
    }
    
    /**
     * Verifica si existe un perfil configurado.
     */
    suspend fun hasProfile(): Boolean = dao.getProfileSync() != null
}
