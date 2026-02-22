package com.diabetes.calculator.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object Recordatorio2hScheduler {

    fun uniqueWorkName(registroId: Int): String = "recordatorio_2h_$registroId"

    fun schedule(
        workManager: WorkManager,
        registroId: Int,
        triggerAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val delayMillis = (triggerAtMillis - nowMillis).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<Recordatorio2hWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(Recordatorio2hWorker.KEY_REGISTRO_ID to registroId))
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(registroId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(
        workManager: WorkManager,
        registroId: Int
    ) {
        workManager.cancelUniqueWork(uniqueWorkName(registroId))
    }
}

