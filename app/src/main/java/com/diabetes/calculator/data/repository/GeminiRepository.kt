package com.diabetes.calculator.data.repository

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.CancellationException

class GeminiRepository {

    @Volatile
    var lastErrorMessage: String? = null
        private set

    suspend fun generateHealthReport(
        model: String,
        prompt: String
    ): String? {
        if (prompt.isBlank()) {
            lastErrorMessage = "Prompt vacío."
            return null
        }

        return try {
            val sanitizedModel = model.removePrefix("models/").trim().ifBlank { DEFAULT_MODEL }
            val response = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(modelName = sanitizedModel)
                .generateContent(prompt)

            val text = response.text?.trim()
            if (text.isNullOrBlank()) {
                lastErrorMessage = "Gemini devolvió una respuesta vacía."
                null
            } else {
                lastErrorMessage = null
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastErrorMessage = mapError(e)
            null
        }
    }

    private fun mapError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.contains("Default FirebaseApp is not initialized", ignoreCase = true) -> {
                "Firebase no configurado. Añade google-services.json en app/ y vuelve a compilar."
            }

            message.contains("App Check", ignoreCase = true) ||
                message.contains("appcheck", ignoreCase = true) -> {
                "App Check rechazó la solicitud. Revisa configuración de Play Integrity en Firebase."
            }

            message.contains("PERMISSION_DENIED", ignoreCase = true) ||
                message.contains("forbidden", ignoreCase = true) ||
                message.contains("API key", ignoreCase = true) -> {
                "Firebase AI sin permisos. Revisa la configuración del proyecto en Firebase."
            }

            message.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("429") -> {
                "Cuota de Gemini agotada temporalmente."
            }

            message.contains("network", ignoreCase = true) ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) -> {
                "Error de red al consultar Gemini."
            }

            message.isNotBlank() -> message
            else -> "Error inesperado consultando Firebase AI."
        }
    }

    companion object {
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
