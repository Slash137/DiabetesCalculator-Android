package com.diabetes.calculator

import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.diabetes.calculator.nfc.NovoPenImportResult
import com.diabetes.calculator.nfc.NovoPenNfcSyncService
import com.diabetes.calculator.ui.navigation.DiabetesNavGraph
import com.diabetes.calculator.ui.theme.DiabetesCalculatorTheme
import androidx.work.WorkManager
import net.cacheux.nvplib.data.PenResult
import net.cacheux.nvplib.nfc.NfcController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Activity principal de la aplicación.
 * Usa Jetpack Compose para toda la UI.
 */
class MainActivity : ComponentActivity() {
    private lateinit var app: DiabetesApp
    private lateinit var novoPenNfcSyncService: NovoPenNfcSyncService
    private var nfcController: NfcController? = null
    private var nfcReaderActive = false
    private val nfcImportInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Obtener la instancia de la aplicación para acceder a los repositorios
        app = application as DiabetesApp
        novoPenNfcSyncService = NovoPenNfcSyncService(
            usuarioRepository = app.usuarioRepository,
            registroRepository = app.registroRepository,
            queueRepository = app.registroNightscoutSyncRepository,
            nightscoutTreatmentTombstoneRepository = app.nightscoutTreatmentTombstoneRepository,
            libreviewQueueRepository = app.registroLibreviewSyncRepository,
            workManager = WorkManager.getInstance(this)
        )

        setContent {
            DiabetesCalculatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DiabetesNavGraph(app = app)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startNovoPenNfcMonitoring()
    }

    override fun onPause() {
        stopNovoPenNfcMonitoring()
        super.onPause()
    }

    private fun startNovoPenNfcMonitoring() {
        if (nfcReaderActive) return
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        if (!adapter.isEnabled) return

        runCatching {
            val controller = NfcController(this)
            controller.monitorNfc(
                onDataRead = { result ->
                    when (result) {
                        is PenResult.Success -> importNovoPenData(result)
                        is PenResult.Failure -> {
                            showToast("Lectura NFC de NovoPen fallida")
                        }
                    }
                },
                onError = { error ->
                    // Ignore non-pen tags to avoid noisy toasts when NFC is enabled.
                    if (!error.message.orEmpty().contains("Incorrect tag", ignoreCase = true)) {
                        showToast("Error NFC: ${error.message ?: "desconocido"}")
                    }
                }
            )
            nfcController = controller
            nfcReaderActive = true
        }
    }

    private fun stopNovoPenNfcMonitoring() {
        nfcController?.stopNfc()
        nfcReaderActive = false
    }

    private fun importNovoPenData(result: PenResult.Success) {
        if (!nfcImportInFlight.compareAndSet(false, true)) return

        lifecycleScope.launch {
            try {
                val importResult = novoPenNfcSyncService.importPenData(result.data)
                showImportToast(importResult)
            } catch (_: Exception) {
                showToast("No se pudo importar la dosis del NovoPen")
            } finally {
                nfcImportInFlight.set(false)
            }
        }
    }

    private fun showImportToast(result: NovoPenImportResult) {
        val message = when {
            result.insertedCount <= 0 -> "NovoPen leído: no hay dosis nuevas"
            result.nightscoutSyncTriggered && result.libreviewSyncTriggered ->
                "Importadas ${result.insertedCount} dosis. Sincronizando Nightscout y LibreView."
            result.nightscoutSyncTriggered ->
                "Importadas ${result.insertedCount} dosis. Sincronizando Nightscout."
            result.libreviewSyncTriggered ->
                "Importadas ${result.insertedCount} dosis. Sincronizando LibreView."
            else -> "Importadas ${result.insertedCount} dosis de NovoPen"
        }
        showToast(message)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
