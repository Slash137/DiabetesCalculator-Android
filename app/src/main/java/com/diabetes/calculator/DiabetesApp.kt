package com.diabetes.calculator

import android.app.Application
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.PendingGlucoseRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.util.BackupManager
import com.diabetes.calculator.util.NightscoutTokenStore
import com.diabetes.calculator.work.AutoBackupWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Clase Application para centralizar la inyección de dependencias manual.
 */
class DiabetesApp : Application() {
    
    // Base de Datos
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Almacenamiento seguro
    private val nightscoutTokenStore by lazy { NightscoutTokenStore(this) }
    
    // Repositorios
    val usuarioRepository: UsuarioProfileRepository by lazy {
        UsuarioProfileRepository(database.usuarioProfileDao(), nightscoutTokenStore)
    }
    val alimentoRepository: AlimentoRepository by lazy { AlimentoRepository(database.alimentoDao()) }
    val registroRepository: RegistroComidaRepository by lazy { RegistroComidaRepository(database.registroComidaDao()) }
    val plantillaRepository: PlantillaRepository by lazy { PlantillaRepository(database.plantillaDao()) }
    val pendingGlucoseRepository: PendingGlucoseRepository by lazy {
        PendingGlucoseRepository(database.pendingGlucoseDao())
    }
    
    // Managers
    val backupManager: BackupManager by lazy { BackupManager(database, nightscoutTokenStore) }
    val nightscoutRepository: NightscoutRepository by lazy { NightscoutRepository() }

    override fun onCreate() {
        super.onCreate()
        // Poblar base de datos si es necesario
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            database.populateDatabase()
            usuarioRepository.migrateTokenIfNeeded()
        }
        scheduleAutoBackup()
    }

    private fun scheduleAutoBackup() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
