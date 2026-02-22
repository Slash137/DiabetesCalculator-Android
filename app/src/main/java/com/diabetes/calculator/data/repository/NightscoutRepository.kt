package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.model.NightscoutCreateTreatmentRequest
import com.diabetes.calculator.data.model.NightscoutEntry
import com.diabetes.calculator.data.model.NightscoutRawEntry
import com.diabetes.calculator.data.model.NightscoutTreatment
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

interface NightscoutApi {
    @GET("api/v1/entries/sgv.json")
    suspend fun getRecentEntries(
        @Query("count") count: Int = 1,
        @Query("token") token: String? = null
    ): List<NightscoutEntry>

    @GET("api/v1/entries/sgv.json")
    suspend fun getEntriesInRange(
        @Query("find[date][\$gte]") from: Long,
        @Query("find[date][\$lte]") to: Long,
        @Query("count") count: Int = 50,
        @Query("skip") skip: Int = 0,
        @Query("token") token: String? = null
    ): List<NightscoutEntry>

    @GET("api/v1/entries.json")
    suspend fun getRawEntriesInRange(
        @Query("find[date][\$gte]") from: Long,
        @Query("find[date][\$lte]") to: Long,
        @Query("count") count: Int = 100,
        @Query("skip") skip: Int = 0,
        @Query("token") token: String? = null
    ): List<NightscoutRawEntry>

    @GET("api/v1/entries.json")
    suspend fun getRecentRawEntries(
        @Query("count") count: Int = 100,
        @Query("skip") skip: Int = 0,
        @Query("token") token: String? = null
    ): List<NightscoutRawEntry>

    @GET("api/v1/treatments.json")
    suspend fun getTreatmentsInRangeByCreatedAt(
        @Query("find[created_at][\$gte]") fromIso: String,
        @Query("find[created_at][\$lte]") toIso: String,
        @Query("count") count: Int = 100,
        @Query("skip") skip: Int = 0,
        @Query("token") token: String? = null
    ): List<NightscoutTreatment>

    @GET("api/v1/treatments.json")
    suspend fun getTreatmentsInRangeByMills(
        @Query("find[mills][\$gte]") fromMillis: Long,
        @Query("find[mills][\$lte]") toMillis: Long,
        @Query("count") count: Int = 100,
        @Query("skip") skip: Int = 0,
        @Query("token") token: String? = null
    ): List<NightscoutTreatment>

    @POST("api/v1/treatments")
    suspend fun createTreatment(
        @Body request: NightscoutCreateTreatmentRequest,
        @Query("token") token: String? = null
    ): NightscoutTreatment
}

/**
 * Repositorio para interactuar con la API de Nightscout.
 */
class NightscoutRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private var currentUrl: String? = null
    private var api: NightscoutApi? = null

    @Volatile
    var lastErrorMessage: String? = null
        private set

    private fun getClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun ensureApi(baseUrl: String): NightscoutApi {
        val normalizedUrl = normalizeUrl(baseUrl)
        if (api == null || currentUrl != normalizedUrl) {
            currentUrl = normalizedUrl
            val retrofit = Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(getClient())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
            api = retrofit.create(NightscoutApi::class.java)
        }
        return requireNotNull(api)
    }

    private fun normalizeUrl(baseUrl: String): String {
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    /**
     * Obtiene el último valor de glucosa desde Nightscout.
     */
    suspend fun getLatestGlucose(baseUrl: String, token: String?): NightscoutEntry? {
        return try {
            val entries = ensureApi(baseUrl).getRecentEntries(count = 1, token = token)
            lastErrorMessage = null
            entries.firstOrNull()
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            null
        }
    }

    suspend fun getRecentGlucoseEntries(
        baseUrl: String,
        token: String?,
        count: Int = 3
    ): List<NightscoutEntry> {
        return try {
            val safeCount = count.coerceIn(1, 24)
            val entries = ensureApi(baseUrl).getRecentEntries(count = safeCount, token = token)
            lastErrorMessage = null
            entries
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            emptyList()
        }
    }

    /**
     * Obtiene el valor de glucosa más cercano a un timestamp dentro de una tolerancia.
     */
    suspend fun getGlucoseClosestTo(
        baseUrl: String,
        token: String?,
        targetMillis: Long,
        toleranceMinutes: Int
    ): NightscoutEntry? {
        return try {
            val toleranceMs = toleranceMinutes * 60_000L
            val from = targetMillis - toleranceMs
            val to = targetMillis + toleranceMs
            val entries = ensureApi(baseUrl).getEntriesInRange(
                from = from,
                to = to,
                count = 50,
                token = token
            )
            lastErrorMessage = null
            entries.minByOrNull { kotlin.math.abs(it.date - targetMillis) }
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            null
        }
    }

    /**
     * Obtiene todas las entradas disponibles en un rango paginando por skip/count.
     * Se limita por maxEntries para evitar consumos excesivos.
     */
    suspend fun getEntriesInRangeAll(
        baseUrl: String,
        token: String?,
        from: Long,
        to: Long,
        pageSize: Int = 500,
        maxEntries: Int = 20_000
    ): List<NightscoutEntry> {
        return try {
            val api = ensureApi(baseUrl)
            val all = mutableListOf<NightscoutEntry>()
            var skip = 0
            while (all.size < maxEntries) {
                val remaining = maxEntries - all.size
                val count = minOf(pageSize, remaining)
                val page = api.getEntriesInRange(
                    from = from,
                    to = to,
                    count = count,
                    skip = skip,
                    token = token
                )
                if (page.isEmpty()) break
                all += page
                if (page.size < count) break
                skip += page.size
            }

            lastErrorMessage = null
            all.sortedBy { it.date }
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            emptyList()
        }
    }

    suspend fun getFastInsulinEntriesInRangeAll(
        baseUrl: String,
        token: String?,
        fromMillis: Long,
        toMillis: Long,
        pageSize: Int = 300,
        maxEntries: Int = 10_000
    ): List<NightscoutRawEntry> {
        return try {
            val api = ensureApi(baseUrl)
            val ranged = fetchRawEntriesPaged(
                pageSize = pageSize,
                maxEntries = maxEntries
            ) { skip, count ->
                api.getRawEntriesInRange(
                    from = fromMillis,
                    to = toMillis,
                    count = count,
                    skip = skip,
                    token = token
                )
            }
            val all = if (ranged.isNotEmpty()) {
                ranged
            } else {
                // Fallback para servidores que no interpretan bien find[date] o usan segundos.
                fetchRawEntriesPaged(
                    pageSize = pageSize,
                    maxEntries = maxEntries
                ) { skip, count ->
                    api.getRecentRawEntries(
                        count = count,
                        skip = skip,
                        token = token
                    )
                }
            }

            val filtered = all.filter { entry ->
                val timestamp = resolveEntryMillis(entry) ?: return@filter false
                val units = resolveEntryInsulinUnits(entry)
                units != null &&
                    units > 0f &&
                    !units.isNaN() &&
                    timestamp in fromMillis..toMillis &&
                    isFastInsulin(entry.insulinType)
            }
            lastErrorMessage = null
            filtered
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            emptyList()
        }
    }

    suspend fun getTreatmentsInRangeAll(
        baseUrl: String,
        token: String?,
        fromMillis: Long,
        toMillis: Long,
        pageSize: Int = 200,
        maxEntries: Int = 4_000
    ): List<NightscoutTreatment> {
        return try {
            val api = ensureApi(baseUrl)
            val byMills = fetchTreatmentsPaged(
                pageSize = pageSize,
                maxEntries = maxEntries
            ) { skip, count ->
                api.getTreatmentsInRangeByMills(
                    fromMillis = fromMillis,
                    toMillis = toMillis,
                    count = count,
                    skip = skip,
                    token = token
                )
            }
            val all = if (byMills.isNotEmpty()) {
                byMills
            } else {
                val fromIso = isoFromMillis(fromMillis)
                val toIso = isoFromMillis(toMillis)
                fetchTreatmentsPaged(
                    pageSize = pageSize,
                    maxEntries = maxEntries
                ) { skip, count ->
                    api.getTreatmentsInRangeByCreatedAt(
                        fromIso = fromIso,
                        toIso = toIso,
                        count = count,
                        skip = skip,
                        token = token
                    )
                }
            }

            lastErrorMessage = null
            all.distinctBy { it.id ?: "${it.mills}-${it.createdAt}" }
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            emptyList()
        }
    }

    suspend fun createTreatment(
        baseUrl: String,
        token: String?,
        request: NightscoutCreateTreatmentRequest
    ): NightscoutTreatment? {
        return try {
            val created = ensureApi(baseUrl).createTreatment(request = request, token = token)
            lastErrorMessage = null
            created
        } catch (e: Exception) {
            lastErrorMessage = formatError(e)
            null
        }
    }

    fun resolveTreatmentMillis(treatment: NightscoutTreatment): Long? {
        val mills = parseLongElement(treatment.mills)
        if (mills != null && mills > 0L) return mills
        val date = parseLongElement(treatment.date)
        if (date != null && date > 0L) return date

        parseIsoMillis(treatment.createdAt)?.let { return it }
        parseIsoMillis(treatment.timestamp)?.let { return it }
        return null
    }

    fun resolveEntryMillis(entry: NightscoutRawEntry): Long? {
        val date = parseLongElement(entry.date)
        if (date != null && date > 0L) return date
        parseIsoMillis(entry.dateString)?.let { return it }
        return null
    }

    fun resolveEntryInsulinUnits(entry: NightscoutRawEntry): Float? {
        return parseFloatElement(entry.insulin) ?: parseFloatElement(entry.insulinInjections)
    }

    fun resolveTreatmentInsulinUnits(treatment: NightscoutTreatment): Float? {
        return parseFloatElement(treatment.insulin) ?: parseFloatElement(treatment.insulinInjections)
    }

    private fun isoFromMillis(millis: Long): String {
        return Instant.ofEpochMilli(millis).toString()
    }

    private fun parseIsoMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(normalizeOffset(value)).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                parseWithLegacyFormats(value)
            }
        }
    }

    private suspend fun fetchTreatmentsPaged(
        pageSize: Int,
        maxEntries: Int,
        fetchPage: suspend (skip: Int, count: Int) -> List<NightscoutTreatment>
    ): List<NightscoutTreatment> {
        val all = mutableListOf<NightscoutTreatment>()
        var skip = 0
        while (all.size < maxEntries) {
            val remaining = maxEntries - all.size
            val count = minOf(pageSize, remaining)
            val page = fetchPage(skip, count)
            if (page.isEmpty()) break
            all += page
            if (page.size < count) break
            skip += page.size
        }
        return all
    }

    private suspend fun fetchRawEntriesPaged(
        pageSize: Int,
        maxEntries: Int,
        fetchPage: suspend (skip: Int, count: Int) -> List<NightscoutRawEntry>
    ): List<NightscoutRawEntry> {
        val all = mutableListOf<NightscoutRawEntry>()
        var skip = 0
        while (all.size < maxEntries) {
            val remaining = maxEntries - all.size
            val count = minOf(pageSize, remaining)
            val page = fetchPage(skip, count)
            if (page.isEmpty()) break
            all += page
            if (page.size < count) break
            skip += page.size
        }
        return all
    }

    private fun normalizeOffset(raw: String): String {
        val tzRegex = Regex("([+-]\\d{2})(\\d{2})$")
        return if (tzRegex.containsMatchIn(raw)) {
            raw.replace(tzRegex, "$1:$2")
        } else {
            raw
        }
    }

    private fun parseWithLegacyFormats(raw: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ssZ"
        )
        formats.forEach { pattern ->
            runCatching {
                val formatter = SimpleDateFormat(pattern, Locale.US)
                formatter.parse(raw)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseFloatElement(value: JsonElement?): Float? {
        val primitive = value as? JsonPrimitive ?: return null
        val content = primitive.content.trim()
        if (content.isEmpty()) return null
        return content.replace(',', '.').toFloatOrNull()
    }

    private fun parseLongElement(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        val content = primitive.content.trim()
        if (content.isEmpty()) return null
        content.toLongOrNull()?.let { return normalizeEpoch(it) }
        content.toDoubleOrNull()?.toLong()?.let { return normalizeEpoch(it) }
        return null
    }

    private fun normalizeEpoch(raw: Long): Long {
        // Algunos backends devuelven segundos en lugar de milisegundos.
        return if (raw in 1_000_000_000L..9_999_999_999L) raw * 1000L else raw
    }

    private fun formatError(e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: "sin detalle"
        return "${e.javaClass.simpleName}: $detail"
    }

    private fun isFastInsulin(insulinType: String?): Boolean {
        if (insulinType.isNullOrBlank()) return true
        val normalized = insulinType
            .trim()
            .lowercase(Locale.ROOT)
            .replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")

        val longKeywords = listOf(
            "long", "basal", "lenta", "glargine", "detemir", "degludec",
            "tresiba", "lantus", "toujeo", "levemir", "nph"
        )
        if (longKeywords.any { normalized.contains(it) }) return false

        val fastKeywords = listOf(
            "fast", "rapid", "bolus", "aspart", "lispro", "glulisine",
            "fiasp", "novorapid", "humalog", "apidra", "lyumjev"
        )
        return fastKeywords.any { normalized.contains(it) } || normalized.isNotBlank()
    }
}
