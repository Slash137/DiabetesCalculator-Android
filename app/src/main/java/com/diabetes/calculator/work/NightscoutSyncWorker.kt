package com.diabetes.calculator.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.repository.NightscoutRegistrosSyncService
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.NightscoutTreatmentTombstoneRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.RegistroNightscoutSyncRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.util.NightscoutTokenStore
import java.util.concurrent.TimeUnit

class NightscoutSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val tokenStore = NightscoutTokenStore(applicationContext)
        val profileRepository = UsuarioProfileRepository(database.usuarioProfileDao(), tokenStore)
        val profile = profileRepository.getProfileSync() ?: return Result.success()
        val forceManual = inputData.getBoolean(KEY_FORCE_MANUAL, false)
        if (!forceManual && !profile.nightscoutSyncRegistrosActivo) return Result.success()
        if (profile.nightscoutUrl.isNullOrBlank()) return Result.success()

        val queueRepository = RegistroNightscoutSyncRepository(database.registroNightscoutSyncDao())
        val service = NightscoutRegistrosSyncService(
            registroRepository = RegistroComidaRepository(database.registroComidaDao()),
            queueRepository = queueRepository,
            tombstoneRepository = NightscoutTreatmentTombstoneRepository(database.nightscoutTreatmentTombstoneDao()),
            nightscoutRepository = NightscoutRepository()
        )

        val now = System.currentTimeMillis()
        val manualResyncDays = inputData.getInt(KEY_RESYNC_DAYS, 0)
        val manualIgnoreTombstones = inputData.getBoolean(KEY_IGNORE_TOMBSTONES, false)
        val needsInitialBackfill = profile.nightscoutSyncBackfillDoneAt == null

        val fromMillis = when {
            manualResyncDays > 0 -> now - manualResyncDays * DAY_MILLIS
            forceManual -> now - DEFAULT_BACKFILL_DAYS * DAY_MILLIS
            needsInitialBackfill -> now - DEFAULT_BACKFILL_DAYS * DAY_MILLIS
            else -> now - INCREMENTAL_WINDOW_MILLIS
        }
        val toMillis = now
        val ignoreTombstones = manualResyncDays > 0 && manualIgnoreTombstones

        val runResult = runCatching {
            service.sync(
                profile = profile,
                fromMillis = fromMillis,
                toMillis = toMillis,
                ignoreTombstones = ignoreTombstones
            )
        }.getOrElse {
            val delay = NightscoutRetryPolicy.nextDelayMinutes(1)
            enqueueRetry(WorkManager.getInstance(applicationContext), delay)
            return Result.success()
        }

        if (needsInitialBackfill || manualResyncDays > 0) {
            profileRepository.updateNightscoutBackfillDoneAt(profile.id, now)
        }

        if (runResult.failedPending > 0) {
            val delayMinutes = NightscoutRetryPolicy.nextDelayMinutes(runResult.maxFailedAttempts)
            enqueueRetry(WorkManager.getInstance(applicationContext), delayMinutes)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME_PERIODIC = "nightscout_records_sync_periodic"
        private const val WORK_NAME_NOW = "nightscout_records_sync_now"
        private const val WORK_NAME_RETRY = "nightscout_records_sync_retry"
        private const val KEY_RESYNC_DAYS = "resync_days"
        private const val KEY_IGNORE_TOMBSTONES = "ignore_tombstones"
        private const val KEY_FORCE_MANUAL = "force_manual"
        private const val DEFAULT_BACKFILL_DAYS = 30L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val INCREMENTAL_WINDOW_MILLIS = 24L * 60L * 60L * 1000L

        fun enqueuePeriodic(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NightscoutSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueNow(workManager: WorkManager, forceManual: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NightscoutSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_FORCE_MANUAL to forceManual
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueResync30Days(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NightscoutSyncWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_RESYNC_DAYS to 30,
                        KEY_IGNORE_TOMBSTONES to true,
                        KEY_FORCE_MANUAL to true
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueRetry(workManager: WorkManager, delayMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NightscoutSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_RETRY,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
