package com.diabetes.calculator.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.diabetes.calculator.R
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.util.DateUtils

/**
 * Recordatorio manual para medir glucosa 2 h después de una comida.
 */
class Recordatorio2hWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val registroId = inputData.getInt(KEY_REGISTRO_ID, -1)
        if (registroId <= 0) return Result.failure()

        val database = AppDatabase.getDatabase(applicationContext)
        val registro = database.registroComidaDao().getById(registroId)?.registro ?: return Result.success()

        val timeLabel = DateUtils.formatTime(registro.fecha + TWO_HOURS_MS)
        val title = "Recordatorio de glucosa"
        val message = "Mide tu glucosa 2 h después de la comida ($timeLabel)."

        showNotification(registroId, title, message)
        return Result.success()
    }

    private fun showNotification(registroId: Int, title: String, message: String) {
        val channelId = CHANNEL_ID
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Recordatorios",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        manager.notify(registroId + 20000, notification)
    }

    companion object {
        const val KEY_REGISTRO_ID = "registro_id"
        private const val CHANNEL_ID = "recordatorios_2h"
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
    }
}
