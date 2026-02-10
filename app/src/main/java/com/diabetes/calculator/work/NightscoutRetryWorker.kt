package com.diabetes.calculator.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.entity.PendingGlucoseTipo
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.RegistroComidaRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.util.NightscoutTokenStore
import java.util.concurrent.TimeUnit

/**
 * Worker para reintentar sincronizaciones pendientes con Nightscout.
 */
class NightscoutRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val pendingDao = database.pendingGlucoseDao()
        val registroRepo = RegistroComidaRepository(database.registroComidaDao())
        val profileRepo = UsuarioProfileRepository(database.usuarioProfileDao(), NightscoutTokenStore(applicationContext))
        val nightscoutRepo = NightscoutRepository()

        val profile = profileRepo.getProfileSync() ?: return Result.success()
        val url = profile.nightscoutUrl ?: return Result.success()
        val token = profile.nightscoutToken

        val pending = pendingDao.getAll()
        if (pending.isEmpty()) return Result.success()

        pending.forEach { item ->
            var errorMessage: String? = null
            val result = try {
                nightscoutRepo.getGlucoseClosestTo(
                    baseUrl = url,
                    token = token,
                    targetMillis = item.targetMillis,
                    toleranceMinutes = TOLERANCE_MINUTES
                )
            } catch (e: Exception) {
                errorMessage = e.message ?: "Error de conexión"
                null
            }

            if (result != null) {
                when (item.tipo) {
                    PendingGlucoseTipo.ANTES -> registroRepo.updateGlucosaAntes(item.registroId, result.sgv)
                    PendingGlucoseTipo.DESPUES_2H -> registroRepo.updateGlucosaDespues2h(item.registroId, result.sgv)
                }
                pendingDao.deleteById(item.id)
            } else {
                val nextAttempts = item.attempts + 1
                if (nextAttempts >= NightscoutRetryPolicy.MAX_ATTEMPTS) {
                    pendingDao.deleteById(item.id)
                } else {
                    pendingDao.update(
                        item.copy(
                            attempts = nextAttempts,
                            lastError = errorMessage ?: "Sin datos"
                        )
                    )
                }
            }
        }

        val remaining = pendingDao.getAll()
        if (remaining.isNotEmpty()) {
            val maxAttempts = remaining.maxOf { it.attempts }
            val delayMinutes = NightscoutRetryPolicy.nextDelayMinutes(maxAttempts)
            enqueue(WorkManager.getInstance(applicationContext), delayMinutes)
        }

        return Result.success()
    }

    companion object {
        private const val TOLERANCE_MINUTES = 15
        private const val WORK_NAME = "nightscout_retry"

        fun enqueue(workManager: WorkManager, delayMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NightscoutRetryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
