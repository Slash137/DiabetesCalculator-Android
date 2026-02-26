package com.diabetes.calculator

import android.app.Application
import android.util.Log
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.repository.AlimentoRepository
import com.diabetes.calculator.data.repository.GeminiRepository
import com.diabetes.calculator.data.repository.LibreviewRecordCatalogRepository
import com.diabetes.calculator.data.repository.LibreviewRepairRunRepository
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.PendingGlucoseRepository
import com.diabetes.calculator.data.repository.PlantillaRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroLibreviewSyncRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.domain.SyncLinkTolerance
import com.diabetes.calculator.util.BackupManager
import com.diabetes.calculator.util.LibreviewSecretStore
import com.diabetes.calculator.util.NightscoutTokenStore
import com.diabetes.calculator.work.AutoBackupWorker
import com.diabetes.calculator.work.LibreviewSyncWorker
import com.diabetes.calculator.work.NightscoutSyncWorker
import com.google.firebase.Firebase
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.initialize
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlin.math.max
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
    private val libreviewSecretStore by lazy { LibreviewSecretStore(this) }
    
    // Repositorios
    val usuarioRepository: UsuarioProfileRepository by lazy {
        UsuarioProfileRepository(
            database.usuarioProfileDao(),
            nightscoutTokenStore,
            libreviewSecretStore
        )
    }
    val alimentoRepository: AlimentoRepository by lazy { AlimentoRepository(database.alimentoDao()) }
    val registroRepository: RegistroComidaRepository by lazy { RegistroComidaRepository(database.registroComidaDao()) }
    val plantillaRepository: PlantillaRepository by lazy { PlantillaRepository(database.plantillaDao()) }
    val pendingGlucoseRepository: PendingGlucoseRepository by lazy {
        PendingGlucoseRepository(database.pendingGlucoseDao())
    }
    val registroNightscoutSyncRepository: RegistroNightscoutSyncRepository by lazy {
        RegistroNightscoutSyncRepository(database.registroNightscoutSyncDao())
    }
    val registroLibreviewSyncRepository: RegistroLibreviewSyncRepository by lazy {
        RegistroLibreviewSyncRepository(database.registroLibreviewSyncDao())
    }
    val libreviewRecordCatalogRepository: LibreviewRecordCatalogRepository by lazy {
        LibreviewRecordCatalogRepository(database.libreviewRecordCatalogDao())
    }
    val libreviewRepairRunRepository: LibreviewRepairRunRepository by lazy {
        LibreviewRepairRunRepository(database.libreviewRepairRunDao())
    }
    val nightscoutTreatmentTombstoneRepository: NightscoutTreatmentTombstoneRepository by lazy {
        NightscoutTreatmentTombstoneRepository(database.nightscoutTreatmentTombstoneDao())
    }
    
    // Managers
    val backupManager: BackupManager by lazy { BackupManager(database, nightscoutTokenStore) }
    val nightscoutRepository: NightscoutRepository by lazy { NightscoutRepository() }
    val geminiRepository: GeminiRepository by lazy { GeminiRepository() }

    override fun onCreate() {
        super.onCreate()
        initializeFirebaseAi()
        CoroutineScope(Dispatchers.IO).launch {
            val workManager = WorkManager.getInstance(this@DiabetesApp)
            scheduleAutoBackup(workManager)
            NightscoutSyncWorker.enqueuePeriodic(workManager)
            LibreviewSyncWorker.enqueuePeriodic(workManager)

            if (shouldPopulateSeedData()) {
                database.populateDatabase()
                markSeedDataPopulated()
            }

            usuarioRepository.migrateTokenIfNeeded()
            val profile = usuarioRepository.getProfileSync()
            val globalLinkMinutes = max(
                profile?.nightscoutLinkOffsetMinutes?.coerceIn(0, 180) ?: SyncLinkTolerance.WINDOW_MINUTES,
                SyncLinkTolerance.WINDOW_MINUTES
            )
            val globalLinkUnits = max(
                profile?.nightscoutLinkOffsetUnits?.coerceIn(0f, 5f) ?: SyncLinkTolerance.WINDOW_UNITS,
                SyncLinkTolerance.WINDOW_UNITS
            )
            NightscoutRegistrosSyncService(
                registroRepository = registroRepository,
                queueRepository = registroNightscoutSyncRepository,
                tombstoneRepository = nightscoutTreatmentTombstoneRepository,
                nightscoutRepository = nightscoutRepository,
                libreviewQueueRepository = registroLibreviewSyncRepository
            ).reconcileLocalDuplicatesOnly(
                linkOffsetMinutes = globalLinkMinutes,
                linkOffsetUnits = globalLinkUnits
            )
            if (profile?.nightscoutSyncRegistrosActivo == true) {
                NightscoutSyncWorker.enqueueNow(workManager)
            }
            if (profile?.libreviewSyncActivo == true) {
                LibreviewSyncWorker.enqueueNow(workManager)
            }
        }
    }

    private fun initializeFirebaseAi() {
        runCatching {
            Firebase.initialize(context = this)
            installAppCheckProviderFactory()
        }.onFailure { error ->
            Log.w(TAG, "No se pudo inicializar Firebase AI", error)
        }
    }

    private fun installAppCheckProviderFactory() {
        val providerClassName = if (BuildConfig.DEBUG) {
            DEBUG_APP_CHECK_PROVIDER
        } else {
            PLAY_INTEGRITY_PROVIDER
        }

        val providerFactory = runCatching {
            val providerClass = Class.forName(providerClassName)
            val getInstanceMethod = providerClass.getMethod("getInstance")
            getInstanceMethod.invoke(null)
        }.getOrNull()

        if (providerFactory is AppCheckProviderFactory) {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(providerFactory)
        } else {
            Log.w(TAG, "No se pudo instalar App Check provider: $providerClassName")
        }
    }

    private fun scheduleAutoBackup(workManager: WorkManager) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "auto_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun shouldPopulateSeedData(): Boolean {
        val prefs = getSharedPreferences(INIT_PREFS, MODE_PRIVATE)
        val seededVersion = prefs.getInt(KEY_FOOD_SEED_VERSION, 0)
        return seededVersion < CURRENT_FOOD_SEED_VERSION
    }

    private fun markSeedDataPopulated() {
        getSharedPreferences(INIT_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_FOOD_SEED_VERSION, CURRENT_FOOD_SEED_VERSION)
            .apply()
    }

    companion object {
        private const val TAG = "DiabetesApp"
        private const val INIT_PREFS = "app_init"
        private const val KEY_FOOD_SEED_VERSION = "food_seed_version"
        private const val CURRENT_FOOD_SEED_VERSION = 1
        private const val PLAY_INTEGRITY_PROVIDER =
            "com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory"
        private const val DEBUG_APP_CHECK_PROVIDER =
            "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
    }
}
