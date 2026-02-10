package com.diabetes.calculator.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.repository.NightscoutRepository
import com.diabetes.calculator.data.repository.UsuarioProfileRepository
import com.diabetes.calculator.data.entity.PendingGlucose
import com.diabetes.calculator.data.entity.PendingGlucoseTipo
import com.diabetes.calculator.util.NightscoutRetryPolicy
import com.diabetes.calculator.util.NightscoutTokenStore
import java.util.concurrent.TimeUnit

/**
 * Worker que actualiza la glucosa 2h después de guardar una comida.
 */
class Glucosa2hWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val registroId = inputData.getInt(KEY_REGISTRO_ID, -1)
        if (registroId <= 0) return Result.failure()

        val database = AppDatabase.getDatabase(applicationContext)
        val registroDao = database.registroComidaDao()

        val registro = registroDao.getById(registroId)?.registro ?: return Result.success()
        if (registro.glucosaDespues2hMgdl != null) return Result.success()

        val tokenStore = NightscoutTokenStore(applicationContext)
        val usuarioRepository = UsuarioProfileRepository(database.usuarioProfileDao(), tokenStore)
        val profile = usuarioRepository.getProfileSync() ?: return Result.success()

        val url = profile.nightscoutUrl
        if (url.isNullOrBlank()) return Result.success()

        val targetMillis = registro.fecha + TimeUnit.HOURS.toMillis(2)
        val entry = NightscoutRepository().getGlucoseClosestTo(
            baseUrl = url,
            token = profile.nightscoutToken,
            targetMillis = targetMillis,
            toleranceMinutes = TOLERANCE_MINUTES
        )

        val sgv = entry?.sgv ?: run {
            val now = System.currentTimeMillis()
            return if (now < targetMillis + TimeUnit.MINUTES.toMillis(TOLERANCE_MINUTES.toLong())) {
                Result.retry()
            } else {
                val pendingDao = database.pendingGlucoseDao()
                pendingDao.insert(
                    PendingGlucose(
                        registroId = registroId,
                        tipo = PendingGlucoseTipo.DESPUES_2H,
                        targetMillis = targetMillis
                    )
                )
                val delayMinutes = NightscoutRetryPolicy.nextDelayMinutes(0)
                NightscoutRetryWorker.enqueue(WorkManager.getInstance(applicationContext), delayMinutes)
                Result.success()
            }
        }

        registroDao.updateGlucosaDespues2h(registroId, sgv)
        return Result.success()
    }

    companion object {
        const val KEY_REGISTRO_ID = "registro_id"
        private const val TOLERANCE_MINUTES = 10
    }
}
