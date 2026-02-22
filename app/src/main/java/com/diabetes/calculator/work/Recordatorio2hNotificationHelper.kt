package com.diabetes.calculator.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.diabetes.calculator.MainActivity
import com.diabetes.calculator.R

object Recordatorio2hNotificationHelper {

    private const val CHANNEL_ID = "recordatorios_2h"
    private const val CHANNEL_NAME = "Recordatorios"
    private const val CHANNEL_DESCRIPTION = "Avisos de control de glucosa a las 2h"
    private const val REAL_NOTIFICATION_OFFSET = 20_000
    private const val TEST_NOTIFICATION_ID = 92_000

    fun showRealNotification(
        context: Context,
        registroId: Int,
        title: String,
        contentText: String,
        bigText: String
    ) {
        showNotification(
            context = context,
            notificationId = registroId + REAL_NOTIFICATION_OFFSET,
            title = title,
            contentText = contentText,
            bigText = bigText
        )
    }

    fun showTestNotificationNow(context: Context) {
        val title = "Control de glucosa a las 2h (prueba)"
        val contentText = "Glucosa 212 mg/dL · Corrección sugerida 1.5 U"
        val bigText = buildString {
            appendLine("Notificación de prueba para validar diseño (sin workers).")
            appendLine("Hora objetivo: 2h tras dosis (prueba)")
            appendLine("Glucosa actual: 212 mg/dL")
            appendLine("Objetivo: 110 mg/dL")
            appendLine("Factor de corrección: 50 mg/dL/U")
            appendLine("Insulina activa estimada: 0.8 U")
            append("Corrección orientativa sugerida: 1.5 U")
        }
        showNotification(
            context = context,
            notificationId = TEST_NOTIFICATION_ID,
            title = title,
            contentText = contentText,
            bigText = bigText
        )
    }

    private fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        contentText: String,
        bigText: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(notificationId, notification)
    }
}

