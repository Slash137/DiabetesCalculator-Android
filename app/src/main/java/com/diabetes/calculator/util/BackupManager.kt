package com.diabetes.calculator.util

import androidx.room.withTransaction
import com.diabetes.calculator.data.database.AppDatabase
import com.diabetes.calculator.data.model.BackupData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clase encargada de la lógica de exportación e importación de datos.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupManager(
    private val database: AppDatabase,
    private val tokenStore: NightscoutTokenStore
) {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Exporta todos los datos de la base de datos a un flujo.
     */
    suspend fun exportData(outputStream: OutputStream, password: String) = withContext(Dispatchers.IO) {
        require(password.isNotBlank()) { "Contraseña requerida" }
        val perfil = database.usuarioProfileDao().getProfileSync()?.copy(nightscoutToken = null)
        val alimentos = database.alimentoDao().getAllSync()
        val registros = database.registroComidaDao().getAllRegistrosRaw()
        val items = database.registroComidaDao().getAllItemsRaw()
        val plantillas = database.plantillaDao().getAllRaw()
        val plantillaItems = database.plantillaDao().getAllItemsRaw()
        
        val backup = BackupData(
            perfil = perfil,
            alimentos = alimentos,
            registros = registros,
            items = items,
            plantillas = plantillas,
            plantillaItems = plantillaItems
        )

        createEncryptedOutputStream(outputStream, password).use { encryptedOut ->
            json.encodeToStream(backup, encryptedOut)
        }
    }

    /**
     * Exporta los datos a CSV (registros y alimentos asociados).
     */
    suspend fun exportCsv(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val registros = database.registroComidaDao().getAllRegistrosRaw()
        val items = database.registroComidaDao().getAllItemsRaw()
        val alimentos = database.alimentoDao().getAllSync().associateBy { it.id }
        val itemsByRegistro = items.groupBy { it.registroId }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("es", "ES"))

        // BOM para compatibilidad con Excel
        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.appendLine(
                listOf(
                    "registro_id",
                    "fecha",
                    "alimento",
                    "gramos_consumidos",
                    "hidratos_item",
                    "hidratos_totales",
                    "raciones_totales",
                    "insulina_total",
                    "ratio_u_g",
                    "glucosa_antes",
                    "glucosa_despues_2h",
                    "franja_horaria",
                    "nivel_estres",
                    "nivel_enfermedad",
                    "fase_ciclo",
                    "nivel_ejercicio",
                    "factor_contexto_raw",
                    "factor_contexto_aplicado",
                    "factor_contexto_capado",
                    "dosis_estado",
                    "dosis_confirmada_at",
                    "notas"
                ).joinToString(";")
            )

            registros.forEach { registro ->
                val fecha = dateFormat.format(registro.fecha)
                val dosisConfirmada = registro.dosisConfirmadaAt?.let { dateFormat.format(it) } ?: ""
                val ratio = registro.ratioInsulinaHc ?: if (registro.hidratosTotales > 0f) {
                    registro.unidadesInsulina / registro.hidratosTotales
                } else {
                    null
                }
                val registroItems = itemsByRegistro[registro.id]

                if (registroItems.isNullOrEmpty()) {
                    writer.appendLine(
                        listOf(
                            registro.id.toString(),
                            fecha,
                            "",
                            "",
                            "",
                            formatFloat(registro.hidratosTotales),
                            formatFloat(registro.racionesCalculadas),
                            formatFloat(registro.unidadesInsulina),
                            formatFloat(ratio),
                            registro.glucosaAntesMgdl?.toString() ?: "",
                            registro.glucosaDespues2hMgdl?.toString() ?: "",
                            registro.franjaHorariaUsada ?: "",
                            registro.nivelEstresUsado ?: "",
                            registro.nivelEnfermedadUsado ?: "",
                            registro.faseCicloUsada ?: "",
                            registro.nivelEjercicioUsado ?: "",
                            formatFloat(registro.factorContextoTotalRaw),
                            formatFloat(registro.factorContextoTotalAplicado),
                            if (registro.factorContextoCapado) "1" else "0",
                            registro.dosisEstado,
                            dosisConfirmada,
                            registro.notas ?: ""
                        ).joinToString(";") { escapeCsv(it) }
                    )
                } else {
                    registroItems.forEach { item ->
                        val alimentoNombre = alimentos[item.alimentoId]?.nombre ?: "Desconocido"
                        writer.appendLine(
                            listOf(
                                registro.id.toString(),
                                fecha,
                                alimentoNombre,
                                formatFloat(item.gramosConsumidos),
                                formatFloat(item.hidratosCalculados),
                                formatFloat(registro.hidratosTotales),
                                formatFloat(registro.racionesCalculadas),
                                formatFloat(registro.unidadesInsulina),
                                formatFloat(ratio),
                                registro.glucosaAntesMgdl?.toString() ?: "",
                                registro.glucosaDespues2hMgdl?.toString() ?: "",
                                registro.franjaHorariaUsada ?: "",
                                registro.nivelEstresUsado ?: "",
                                registro.nivelEnfermedadUsado ?: "",
                                registro.faseCicloUsada ?: "",
                                registro.nivelEjercicioUsado ?: "",
                                formatFloat(registro.factorContextoTotalRaw),
                                formatFloat(registro.factorContextoTotalAplicado),
                                if (registro.factorContextoCapado) "1" else "0",
                                registro.dosisEstado,
                                dosisConfirmada,
                                registro.notas ?: ""
                            ).joinToString(";") { escapeCsv(it) }
                        )
                    }
                }
            }
        }
    }

    /**
     * Importa los datos de un flujo a la base de datos.
     * ADVERTENCIA: Borra los datos actuales.
     */
    suspend fun importData(inputStream: InputStream, password: String?) = withContext(Dispatchers.IO) {
        val backup = decodeBackup(inputStream, password)
        
        // Usar withTransaction para asegurar atomicidad y permitir corrutinas
        database.withTransaction {
            // 1. Limpiar datos actuales
            database.clearAllTables()
            tokenStore.setToken(null)
            
            // 2. Restaurar Perfil
            backup.perfil?.let { perfil ->
                if (!perfil.nightscoutToken.isNullOrBlank()) {
                    tokenStore.setToken(perfil.nightscoutToken)
                }
                database.usuarioProfileDao().insertProfile(perfil.copy(nightscoutToken = null))
            }
            
            // 3. Restaurar Alimentos
            backup.alimentos.forEach {
                database.alimentoDao().insertAlimento(it)
            }
            
            // 4. Restaurar Registros
            backup.registros.forEach {
                database.registroComidaDao().insertRegistro(it)
            }
            
            // 5. Restaurar Items de registros
            backup.items.forEach {
                database.registroComidaDao().insertItem(it)
            }

            // 6. Restaurar plantillas
            backup.plantillas.forEach {
                database.plantillaDao().insertPlantilla(it)
            }

            // 7. Restaurar items de plantillas
            if (backup.plantillaItems.isNotEmpty()) {
                database.plantillaDao().insertItems(backup.plantillaItems)
            }
        }
    }

    private fun decodeBackup(inputStream: InputStream, password: String?): BackupData {
        val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        buffered.mark(MAGIC_BYTES.size + SALT_SIZE + IV_SIZE + 1)
        val header = ByteArray(MAGIC_BYTES.size)
        val read = buffered.read(header)

        return if (read == MAGIC_BYTES.size && header.contentEquals(MAGIC_BYTES)) {
            if (password.isNullOrBlank()) {
                throw IllegalArgumentException("Se requiere contraseña para esta copia de seguridad")
            }
            val dataIn = DataInputStream(buffered)
            val salt = ByteArray(SALT_SIZE)
            val iv = ByteArray(IV_SIZE)
            dataIn.readFully(salt)
            dataIn.readFully(iv)
            try {
                val cipher = buildCipher(Cipher.DECRYPT_MODE, password, salt, iv)
                CipherInputStream(buffered, cipher).use { cipherIn ->
                    json.decodeFromStream(cipherIn)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Contraseña incorrecta o archivo corrupto")
            }
        } else {
            buffered.reset()
            json.decodeFromStream(buffered)
        }
    }

    private fun createEncryptedOutputStream(outputStream: OutputStream, password: String): OutputStream {
        val salt = randomBytes(SALT_SIZE)
        val iv = randomBytes(IV_SIZE)
        outputStream.write(MAGIC_BYTES)
        outputStream.write(salt)
        outputStream.write(iv)

        val cipher = buildCipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        return CipherOutputStream(outputStream, cipher)
    }

    private fun buildCipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(keySpec).encoded
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun formatFloat(value: Float?): String {
        if (value == null || value.isNaN()) return ""
        return String.format(Locale("es", "ES"), "%.2f", value)
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(';') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    companion object {
        private val MAGIC_BYTES = "DBCALC1".toByteArray(Charsets.US_ASCII)
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val PBKDF2_ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
