package com.diabetes.calculator.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.util.BackupManager
import com.diabetes.calculator.util.BackupPasswordStore
import com.diabetes.calculator.util.NightscoutTokenStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val tokenStore = NightscoutTokenStore(applicationContext)
            val backupManager = BackupManager(database, tokenStore)
            val passwordStore = BackupPasswordStore(applicationContext)
            val password = passwordStore.getOrCreatePassword()

            val backupDir = applicationContext.getExternalFilesDir("backups")
                ?: File(applicationContext.filesDir, "backups")
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale("es", "ES"))
                .format(Date())
            val backupFile = File(backupDir, "auto_backup_$timestamp.json")

            backupFile.outputStream().use { outputStream ->
                backupManager.exportData(outputStream, password)
            }

            cleanupOldBackups(backupDir, keep = 7)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun cleanupOldBackups(directory: File, keep: Int) {
        val backups = directory.listFiles { file ->
            file.isFile && file.name.startsWith("auto_backup_")
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backups.size <= keep) return
        backups.drop(keep).forEach { it.delete() }
    }
}
