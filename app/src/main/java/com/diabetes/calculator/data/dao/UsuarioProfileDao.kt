package com.diabetes.calculator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.diabetes.calculator.data.entity.UsuarioProfile
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones CRUD del perfil de usuario.
 * Solo se permite un perfil activo en la aplicación.
 */
@Dao
interface UsuarioProfileDao {
    
    /**
     * Obtiene el primer perfil de usuario (solo debe haber uno).
     * Retorna Flow para observar cambios reactivamente.
     */
    @Query("SELECT * FROM usuario_profile LIMIT 1")
    fun getProfile(): Flow<UsuarioProfile?>
    
    /**
     * Obtiene el perfil actual de forma síncrona.
     */
    @Query("SELECT * FROM usuario_profile LIMIT 1")
    suspend fun getProfileSync(): UsuarioProfile?
    
    /**
     * Inserta un perfil (usado para restauración).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UsuarioProfile)
    
    /**
     * Actualiza un perfil existente.
     */
    @Update
    suspend fun update(profile: UsuarioProfile)

    @Query("UPDATE usuario_profile SET nightscoutSyncBackfillDoneAt = :timestamp WHERE id = :profileId")
    suspend fun updateNightscoutBackfillDoneAt(profileId: Int, timestamp: Long?)

    @Query("UPDATE usuario_profile SET libreviewBackfillDoneAt = :timestamp WHERE id = :profileId")
    suspend fun updateLibreviewBackfillDoneAt(profileId: Int, timestamp: Long?)
    
    /**
     * Elimina todos los perfiles (para reiniciar).
     */
    @Query("DELETE FROM usuario_profile")
    suspend fun deleteAll()
}
