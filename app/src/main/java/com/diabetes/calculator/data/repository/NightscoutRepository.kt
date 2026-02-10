package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.model.NightscoutEntry
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

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
}

/**
 * Repositorio para interactuar con la API de Nightscout.
 */
class NightscoutRepository {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private var currentUrl: String? = null
    private var api: NightscoutApi? = null
    
    private fun getClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Obtiene el último valor de glucosa desde Nightscout.
     */
    suspend fun getLatestGlucose(baseUrl: String, token: String?): NightscoutEntry? {
        try {
            // Asegurar que la URL termine en /
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            
            // Reinstanciar Retrofit si la URL cambia
            if (api == null || currentUrl != normalizedUrl) {
                currentUrl = normalizedUrl
                val retrofit = Retrofit.Builder()
                    .baseUrl(normalizedUrl)
                    .client(getClient())
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                api = retrofit.create(NightscoutApi::class.java)
            }
            
            val entries = api?.getRecentEntries(count = 1, token = token)
            return entries?.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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
        try {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            if (api == null || currentUrl != normalizedUrl) {
                currentUrl = normalizedUrl
                val retrofit = Retrofit.Builder()
                    .baseUrl(normalizedUrl)
                    .client(getClient())
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                api = retrofit.create(NightscoutApi::class.java)
            }

            val toleranceMs = toleranceMinutes * 60_000L
            val from = targetMillis - toleranceMs
            val to = targetMillis + toleranceMs
            val entries = api?.getEntriesInRange(from = from, to = to, count = 50, token = token)
            return entries
                ?.minByOrNull { kotlin.math.abs(it.date - targetMillis) }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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
        maxEntries: Int = 20000
    ): List<NightscoutEntry> {
        return try {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            if (api == null || currentUrl != normalizedUrl) {
                currentUrl = normalizedUrl
                val retrofit = Retrofit.Builder()
                    .baseUrl(normalizedUrl)
                    .client(getClient())
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                api = retrofit.create(NightscoutApi::class.java)
            }

            val all = mutableListOf<NightscoutEntry>()
            var skip = 0
            while (all.size < maxEntries) {
                val remaining = maxEntries - all.size
                val count = minOf(pageSize, remaining)
                val page = api?.getEntriesInRange(
                    from = from,
                    to = to,
                    count = count,
                    skip = skip,
                    token = token
                ).orEmpty()

                if (page.isEmpty()) break
                all += page
                if (page.size < count) break
                skip += page.size
            }

            all.sortedBy { it.date }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
