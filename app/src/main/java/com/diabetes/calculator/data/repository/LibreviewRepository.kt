package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.model.LibreviewAuthRequest
import com.diabetes.calculator.data.model.LibreviewAuthResponse
import com.diabetes.calculator.data.model.LibreviewConfigPayload
import com.diabetes.calculator.data.model.LibreviewMeasurementsPageResponse
import com.diabetes.calculator.data.model.LibreviewMeasurementsRequest
import com.diabetes.calculator.data.model.LibreviewPostMeasurementsResponse
import com.diabetes.calculator.data.model.LibreviewRemoteEntry
import com.diabetes.calculator.data.model.LibreviewSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LibreviewConfigResolved(
    val countryCode: String,
    val baseUrl: String,
    val apiKey: String?,
    val domain: String,
    val gatewayType: String
)

data class LibreviewPostResult(
    val success: Boolean,
    val status: Int? = null,
    val reason: String? = null
)

data class LibreviewHttpDiagnostic(
    val method: String,
    val path: String,
    val status: Int?,
    val responseSize: Int,
    val parsedEntries: Int,
    val note: String? = null
)

data class LibreviewReadProbeResult(
    val success: Boolean,
    val endpointId: String? = null,
    val attemptedEndpoints: List<String> = emptyList(),
    val reason: String? = null
)

private data class AuthVariant(
    val domain: String,
    val gatewayType: String
)

class LibreviewRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val client: OkHttpClient
    private val localeProvider: () -> Locale
    private val configBaseUrl: String
    private val diagnosticsLogger: ((LibreviewHttpDiagnostic) -> Unit)?
    @Volatile
    private var discoveredReadEndpoint: ReadEndpointCandidate? = null

    constructor(
        client: OkHttpClient = defaultClient(),
        localeProvider: () -> Locale = { Locale.getDefault() },
        configBaseUrl: String = DEFAULT_CONFIG_BASE_URL,
        diagnosticsLogger: ((LibreviewHttpDiagnostic) -> Unit)? = null
    ) {
        this.client = client
        this.localeProvider = localeProvider
        this.configBaseUrl = configBaseUrl
        this.diagnosticsLogger = diagnosticsLogger
    }

    @Volatile
    var lastErrorMessage: String? = null
        private set

    private data class ReadEndpointCandidate(
        val id: String,
        val method: String,
        val path: String,
        val queryKeys: List<String> = emptyList(),
        val expectsCursor: Boolean = false
    )

    fun resolveCountryCandidates(
        overrideCountry: String?,
        localeCountry: String = Locale.getDefault().country
    ): List<String> {
        val normalizedOverride = normalizeCountry(overrideCountry)
        val normalizedLocale = normalizeCountry(localeCountry)
        val fallback = listOf(
            "US", "GB", "ES", "FR", "DE", "IT", "PT",
            "NL", "BE", "CH", "AT", "IE",
            "SE", "NO", "DK", "FI",
            "CA", "AU", "NZ", "MX", "BR", "AR", "CL", "CO", "PE"
        )
        return buildList {
            if (!normalizedOverride.isNullOrBlank()) add(normalizedOverride)
            if (!normalizedLocale.isNullOrBlank()) add(normalizedLocale)
            addAll(fallback)
        }.distinct()
    }

    suspend fun fetchConfigAuto(
        overrideCountry: String?,
        localeCountry: String = localeProvider().country
    ): LibreviewConfigResolved? {
        val candidates = resolveCountryCandidates(overrideCountry, localeCountry)
        for (country in candidates) {
            val config = fetchConfig(country) ?: continue
            return config
        }
        lastErrorMessage = "No se pudo resolver configuración de LibreView para ninguna región"
        return null
    }

    suspend fun authenticateAuto(
        overrideCountry: String?,
        email: String,
        password: String,
        deviceId: String,
        culture: String = defaultCulture(),
        localeCountry: String = localeProvider().country
    ): LibreviewSession? {
        val candidates = resolveCountryCandidates(overrideCountry, localeCountry)
        var lastAuthError: String? = null
        for (country in candidates) {
            val config = fetchConfig(country)
            if (config == null) {
                lastAuthError = lastErrorMessage
                continue
            }
            val session = authenticateWithConfig(
                config = config,
                email = email,
                password = password,
                deviceId = deviceId,
                culture = culture
            )
            if (session != null) {
                return session
            }
            lastAuthError = lastErrorMessage
        }
        lastErrorMessage = lastAuthError
            ?: "No se pudo autenticar en LibreView con las regiones probadas"
        return null
    }

    suspend fun authenticateWithConfig(
        config: LibreviewConfigResolved,
        email: String,
        password: String,
        deviceId: String,
        culture: String = defaultCulture()
    ): LibreviewSession? {
        val primaryVariant = AuthVariant(domain = config.domain, gatewayType = config.gatewayType)
        val primaryResponse = authenticateWithVariant(
            config = config,
            email = email,
            password = password,
            deviceId = deviceId,
            culture = culture,
            variant = primaryVariant
        ) ?: return null
        val primaryStatus = primaryResponse.status ?: -1
        if (primaryStatus == 0) {
            return buildSession(config = config, response = primaryResponse, variant = primaryVariant)
        }

        var bestError =
            "Autenticación LibreView rechazada: status=$primaryStatus reason=${primaryResponse.reason}"
        if (primaryStatus != STATUS_INVALID_GATEWAY_TYPE &&
            !primaryResponse.reason.equals(REASON_INVALID_GATEWAY_TYPE, ignoreCase = true)
        ) {
            lastErrorMessage = bestError
            return null
        }

        for (variant in fallbackAuthVariants(config, primaryVariant)) {
            val fallbackResponse = authenticateWithVariant(
                config = config,
                email = email,
                password = password,
                deviceId = deviceId,
                culture = culture,
                variant = variant
            ) ?: continue
            val fallbackStatus = fallbackResponse.status ?: -1
            if (fallbackStatus == 0) {
                return buildSession(config = config, response = fallbackResponse, variant = variant)
            }
            bestError =
                "Autenticación LibreView rechazada: status=$fallbackStatus reason=${fallbackResponse.reason} " +
                    "gateway=${variant.gatewayType} domain=${variant.domain}"
        }
        lastErrorMessage = bestError
        return null
    }

    private suspend fun authenticateWithVariant(
        config: LibreviewConfigResolved,
        email: String,
        password: String,
        deviceId: String,
        culture: String,
        variant: AuthVariant
    ): LibreviewAuthResponse? {
        val first = authenticate(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            email = email,
            password = password,
            deviceId = deviceId,
            culture = culture,
            domain = variant.domain,
            gatewayType = variant.gatewayType,
            setDevice = false
        )
        if (first?.status == 0) {
            return first
        }
        return if (isWrongDeviceResponse(first)) {
            authenticate(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                email = email,
                password = password,
                deviceId = deviceId,
                culture = culture,
                domain = variant.domain,
                gatewayType = variant.gatewayType,
                setDevice = true
            )
        } else {
            first
        }
    }

    private fun isWrongDeviceResponse(response: LibreviewAuthResponse?): Boolean {
        if (response == null) return false
        if (response.status != STATUS_WRONG_DEVICE_FOR_USER) return false
        return response.reason.equals(REASON_WRONG_DEVICE_FOR_USER, ignoreCase = true) ||
            response.reason.equals(REASON_WRONG_DEVICE_IN_TOKEN, ignoreCase = true)
    }

    private fun buildSession(
        config: LibreviewConfigResolved,
        response: LibreviewAuthResponse,
        variant: AuthVariant
    ): LibreviewSession? {
        val userToken = response.result?.userToken?.takeIf { it.isNotBlank() }
        if (userToken.isNullOrBlank()) {
            lastErrorMessage = "Autenticación LibreView sin UserToken"
            return null
        }
        lastErrorMessage = null
        return LibreviewSession(
            userToken = userToken,
            accountId = response.result.accountId,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            authenticatedAt = System.currentTimeMillis(),
            countryCode = config.countryCode,
            domain = variant.domain,
            gatewayType = variant.gatewayType
        )
    }

    private fun fallbackAuthVariants(
        config: LibreviewConfigResolved,
        primary: AuthVariant
    ): List<AuthVariant> {
        val variants = linkedSetOf<AuthVariant>()
        gatewayFallbackCandidates(config.gatewayType).forEach { gateway ->
            variants.add(AuthVariant(domain = config.domain, gatewayType = gateway))
        }
        domainFallbackCandidates(config.domain).forEach { domain ->
            variants.add(AuthVariant(domain = domain, gatewayType = config.gatewayType))
        }
        return variants.filter { it != primary }
    }

    private fun gatewayFallbackCandidates(primaryGatewayType: String): List<String> {
        val defaults = listOf(
            primaryGatewayType,
            DEFAULT_GATEWAY_TYPE,
            IOS_GATEWAY_TYPE,
            LIBRE_LINK_UP_ANDROID_GATEWAY_TYPE,
            LIBRE_LINK_UP_IOS_GATEWAY_TYPE
        )
        return defaults.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun domainFallbackCandidates(primaryDomain: String): List<String> {
        val defaults = listOf(primaryDomain, DEFAULT_DOMAIN, LEGACY_DEFAULT_DOMAIN)
        return defaults.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    suspend fun postMeasurements(
        baseUrl: String,
        apiKey: String?,
        userToken: String,
        requestPayload: LibreviewMeasurementsRequest,
        domain: String = DEFAULT_DOMAIN,
        gatewayType: String = DEFAULT_GATEWAY_TYPE
    ): LibreviewPostResult {
        return runCatching {
            val url = "${normalizeBaseUrl(baseUrl)}api/measurements"
            val requestJson = json.encodeToString(
                requestPayload.copy(
                    userToken = userToken,
                    domain = domain,
                    gatewayType = gatewayType
                )
            )
            val request = Request.Builder()
                .url(url)
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept-Language", defaultCulture())
                .header("Abbott-ADC-App-Platform", "Android/14/FSLL/2.12.0")
                .header("GatewayType", gatewayType)
                .apply {
                    if (!apiKey.isNullOrBlank()) {
                        header("x-api-key", apiKey)
                    }
                    header("x-newyu-token", userToken)
                }
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastErrorMessage = "POST /api/measurements HTTP ${response.code}: $responseBody"
                    return LibreviewPostResult(success = false, status = response.code, reason = "http_error")
                }
                val parsed = runCatching {
                    json.decodeFromString<LibreviewPostMeasurementsResponse>(responseBody)
                }.getOrNull()
                val status = parsed?.status ?: 0
                val reason = parsed?.reason
                val success = status == 0 || status == 200
                if (!success) {
                    lastErrorMessage =
                        "LibreView status=$status reason=$reason gateway=$gatewayType domain=$domain"
                } else {
                    lastErrorMessage = null
                }
                LibreviewPostResult(
                    success = success,
                    status = status,
                    reason = reason
                )
            }
        }.getOrElse { error ->
            lastErrorMessage = error.message ?: "Error de red LibreView"
            LibreviewPostResult(success = false, reason = "network_error")
        }
    }

    suspend fun probeMeasurementsReadEndpoint(
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long
    ): LibreviewReadProbeResult {
        val attempted = mutableListOf<String>()
        val candidates = readEndpointCandidates()
        candidates.forEach { candidate ->
            attempted += candidate.id
            val probeResult = runReadRequest(
                candidate = candidate,
                session = session,
                fromMillis = fromMillis,
                toMillis = toMillis,
                limit = PROBE_LIMIT,
                cursor = null,
                requireShape = true
            )
            if (probeResult != null) {
                discoveredReadEndpoint = candidate
                lastErrorMessage = null
                return LibreviewReadProbeResult(
                    success = true,
                    endpointId = candidate.id,
                    attemptedEndpoints = attempted
                )
            }
        }
        val reason = lastErrorMessage ?: "No se detectó endpoint de lectura de measurements"
        return LibreviewReadProbeResult(
            success = false,
            endpointId = null,
            attemptedEndpoints = attempted,
            reason = reason
        )
    }

    suspend fun fetchMeasurementsPage(
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long,
        cursor: String? = null,
        limit: Int = READ_PAGE_LIMIT
    ): LibreviewMeasurementsPageResponse? {
        val endpoint = discoveredReadEndpoint
            ?: run {
                val probe = probeMeasurementsReadEndpoint(
                    session = session,
                    fromMillis = fromMillis,
                    toMillis = toMillis
                )
                if (!probe.success) return null
                discoveredReadEndpoint
            }
            ?: return null

        return runReadRequest(
            candidate = endpoint,
            session = session,
            fromMillis = fromMillis,
            toMillis = toMillis,
            limit = limit,
            cursor = cursor,
            requireShape = false
        )
    }

    suspend fun fetchMeasurements(
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long,
        limitPerPage: Int = READ_PAGE_LIMIT,
        maxPages: Int = READ_MAX_PAGES
    ): List<LibreviewRemoteEntry> {
        val merged = mutableListOf<LibreviewRemoteEntry>()
        var cursor: String? = null
        var pages = 0
        while (pages < maxPages) {
            pages += 1
            val page = fetchMeasurementsPage(
                session = session,
                fromMillis = fromMillis,
                toMillis = toMillis,
                cursor = cursor,
                limit = limitPerPage
            ) ?: break
            merged += page.entries
            val next = page.nextCursor?.trim().orEmpty().ifBlank { null }
            if (next == null || next == cursor) break
            cursor = next
        }
        return merged
    }

    fun findEquivalentRemoteEntry(
        remoteEntries: List<LibreviewRemoteEntry>,
        channel: String,
        recordNumber: Long,
        eventTimestampMillis: Long,
        amountValue: Float,
        timestampToleranceMillis: Long,
        amountTolerance: Float
    ): LibreviewRemoteEntry? {
        val sameChannel = remoteEntries.filter { it.channel == channel }
        if (sameChannel.isEmpty()) return null
        sameChannel.firstOrNull { it.recordNumber == recordNumber }?.let { exact ->
            return exact
        }
        return sameChannel
            .asSequence()
            .mapNotNull { candidate ->
                val candidateTimestamp = candidate.eventTimestampMillis ?: return@mapNotNull null
                val candidateAmount = candidate.amountValue
                if (!candidateAmount.isFinite() || candidateAmount <= 0f) return@mapNotNull null
                val deltaMillis = abs(candidateTimestamp - eventTimestampMillis)
                val deltaAmount = abs(candidateAmount - amountValue)
                if (deltaMillis <= timestampToleranceMillis && deltaAmount <= amountTolerance) {
                    Triple(candidate, deltaMillis, deltaAmount)
                } else {
                    null
                }
            }
            .minWithOrNull(
                compareBy<Triple<LibreviewRemoteEntry, Long, Float>> { it.second }
                    .thenBy { it.third }
            )
            ?.first
    }

    private suspend fun runReadRequest(
        candidate: ReadEndpointCandidate,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long,
        limit: Int,
        cursor: String?,
        requireShape: Boolean
    ): LibreviewMeasurementsPageResponse? {
        return runCatching {
            val request = buildReadRequest(
                candidate = candidate,
                session = session,
                fromMillis = fromMillis,
                toMillis = toMillis,
                limit = limit,
                cursor = cursor
            )
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val parsedEntries = if (response.isSuccessful) {
                    parseRemoteEntries(responseBody).size
                } else {
                    0
                }
                diagnosticsLogger?.invoke(
                    LibreviewHttpDiagnostic(
                        method = candidate.method,
                        path = candidate.path,
                        status = response.code,
                        responseSize = responseBody.length,
                        parsedEntries = parsedEntries,
                        note = if (response.isSuccessful) null else "http_error"
                    )
                )
                if (!response.isSuccessful) {
                    lastErrorMessage =
                        "READ ${candidate.path} HTTP ${response.code}: ${responseBody.take(160)}"
                    return null
                }
                val root = parseJsonSafely(responseBody) ?: run {
                    lastErrorMessage = "READ ${candidate.path}: respuesta no parseable"
                    return null
                }
                val entries = parseRemoteEntries(root)
                val shapeDetected = entries.isNotEmpty() || containsMeasurementShape(root)
                if (requireShape && !shapeDetected) {
                    lastErrorMessage = "READ ${candidate.path}: respuesta sin estructura de measurements"
                    return null
                }
                val page = LibreviewMeasurementsPageResponse(
                    entries = entries,
                    nextCursor = extractNextCursor(root),
                    endpointId = candidate.id
                )
                lastErrorMessage = null
                page
            }
        }.getOrElse { error ->
            diagnosticsLogger?.invoke(
                LibreviewHttpDiagnostic(
                    method = candidate.method,
                    path = candidate.path,
                    status = null,
                    responseSize = 0,
                    parsedEntries = 0,
                    note = "network_error"
                )
            )
            lastErrorMessage = error.message ?: "Error de red en lectura LibreView"
            null
        }
    }

    private fun buildReadRequest(
        candidate: ReadEndpointCandidate,
        session: LibreviewSession,
        fromMillis: Long,
        toMillis: Long,
        limit: Int,
        cursor: String?
    ): Request {
        val urlBuilder = "${normalizeBaseUrl(session.baseUrl)}${candidate.path}"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?: throw IllegalStateException("URL inválida LibreView")

        fun addQuery(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            urlBuilder.addQueryParameter(key, value)
        }

        candidate.queryKeys.forEach { key ->
            when (key) {
                "from" -> addQuery(key, toIso(fromMillis))
                "to" -> addQuery(key, toIso(toMillis))
                "startDate", "start" -> addQuery(key, toIso(fromMillis))
                "endDate", "end" -> addQuery(key, toIso(toMillis))
                "fromEpoch", "startEpoch", "fromTimestamp", "startTimestamp" ->
                    addQuery(key, fromMillis.toString())
                "toEpoch", "endEpoch", "toTimestamp", "endTimestamp" ->
                    addQuery(key, toMillis.toString())
                "limit", "count", "pageSize" -> addQuery(key, limit.toString())
                "cursor", "pageToken" -> addQuery(key, cursor)
            }
        }

        val finalUrl = urlBuilder.build()
        val builder = Request.Builder()
            .url(finalUrl)
            .header("Content-Type", "application/json")
            .header("Accept-Language", defaultCulture())
            .header("Abbott-ADC-App-Platform", "Android/14/FSLL/2.12.0")
            .header("GatewayType", session.gatewayType)
            .apply {
                if (!session.apiKey.isNullOrBlank()) {
                    header("x-api-key", session.apiKey)
                }
                header("x-newyu-token", session.userToken)
            }

        return if (candidate.method == "POST") {
            val body = json.encodeToString(
                buildJsonObject {
                    put("from", toIso(fromMillis))
                    put("to", toIso(toMillis))
                    put("fromEpoch", fromMillis)
                    put("toEpoch", toMillis)
                    put("limit", limit)
                    if (!cursor.isNullOrBlank()) {
                        put("cursor", cursor)
                    }
                }
            )
            builder.post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        } else {
            builder.get().build()
        }
    }

    private fun parseJsonSafely(raw: String): JsonElement? {
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun parseRemoteEntries(raw: String): List<LibreviewRemoteEntry> {
        val root = parseJsonSafely(raw) ?: return emptyList()
        return parseRemoteEntries(root)
    }

    private fun parseRemoteEntries(root: JsonElement): List<LibreviewRemoteEntry> {
        val entries = mutableListOf<LibreviewRemoteEntry>()
        val rootMetadata = extractRootMetadata(root)

        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::visit)
                is JsonObject -> {
                    val parsed = parseRemoteEntryObject(
                        obj = element,
                        rootMetadata = rootMetadata
                    )
                    if (parsed != null) {
                        entries += parsed
                    } else {
                        element.values.forEach(::visit)
                    }
                }
                else -> Unit
            }
        }

        visit(root)
        return entries
    }

    private data class RemoteEntryMetadata(
        val deviceSerial: String? = null,
        val deviceId: String? = null,
        val gatewayType: String? = null,
        val sourceTag: String? = null
    )

    private fun parseRemoteEntryObject(
        obj: JsonObject,
        rootMetadata: RemoteEntryMetadata
    ): LibreviewRemoteEntry? {
        val recordNumber = obj["recordNumber"]?.jsonPrimitive?.longOrNull ?: return null
        val gramsCarbs = obj["gramsCarbs"]?.jsonPrimitive?.floatOrNull
        val units = obj["units"]?.jsonPrimitive?.floatOrNull
        val channel = when {
            gramsCarbs != null -> "CARBS"
            units != null -> "NFC_INSULIN"
            else -> return null
        }
        val amount = gramsCarbs ?: units ?: 0f
        val timestampRaw = obj["timestamp"]?.jsonPrimitive?.contentOrNull
            ?: obj["factoryTimestamp"]?.jsonPrimitive?.contentOrNull
            ?: obj["date"]?.jsonPrimitive?.contentOrNull
            ?: obj["time"]?.jsonPrimitive?.contentOrNull
        val eventMillis = parseTimestampMillis(timestampRaw)
        val objectMetadata = parseMetadataFromObject(obj)
        val extendedMetadata = parseMetadataFromObject(
            obj["extendedProperties"] as? JsonObject
        )
        return LibreviewRemoteEntry(
            channel = channel,
            recordNumber = recordNumber,
            eventTimestampMillis = eventMillis,
            amountValue = amount,
            timestampRaw = timestampRaw,
            deviceSerial = firstNotBlank(
                objectMetadata.deviceSerial,
                extendedMetadata.deviceSerial,
                rootMetadata.deviceSerial
            ),
            deviceId = firstNotBlank(
                objectMetadata.deviceId,
                extendedMetadata.deviceId,
                rootMetadata.deviceId
            ),
            gatewayType = firstNotBlank(
                objectMetadata.gatewayType,
                extendedMetadata.gatewayType,
                rootMetadata.gatewayType
            ),
            sourceTag = firstNotBlank(
                objectMetadata.sourceTag,
                extendedMetadata.sourceTag,
                rootMetadata.sourceTag
            )
        )
    }

    private fun extractRootMetadata(root: JsonElement): RemoteEntryMetadata {
        if (root !is JsonObject) return RemoteEntryMetadata()
        val rootDirect = parseMetadataFromObject(root)
        val rootNested = parseMetadataFromObject(
            (root["meta"] as? JsonObject)
                ?: (root["metadata"] as? JsonObject)
                ?: (root["device"] as? JsonObject)
                ?: (root["context"] as? JsonObject)
                ?: (root["source"] as? JsonObject)
        )
        return RemoteEntryMetadata(
            deviceSerial = firstNotBlank(rootDirect.deviceSerial, rootNested.deviceSerial),
            deviceId = firstNotBlank(rootDirect.deviceId, rootNested.deviceId),
            gatewayType = firstNotBlank(rootDirect.gatewayType, rootNested.gatewayType),
            sourceTag = firstNotBlank(rootDirect.sourceTag, rootNested.sourceTag)
        )
    }

    private fun parseMetadataFromObject(obj: JsonObject?): RemoteEntryMetadata {
        if (obj == null) return RemoteEntryMetadata()
        return RemoteEntryMetadata(
            deviceSerial = extractStringValue(
                obj,
                keys = listOf(
                    "deviceSerial", "serial", "serialNumber", "deviceSN", "insulinDeviceSerial"
                )
            ),
            deviceId = extractStringValue(
                obj,
                keys = listOf(
                    "deviceId", "deviceID", "deviceIdentifier", "sourceDeviceId"
                )
            ),
            gatewayType = extractStringValue(
                obj,
                keys = listOf(
                    "gatewayType", "GatewayType"
                )
            ),
            sourceTag = extractStringValue(
                obj,
                keys = listOf(
                    "sourceTag", "source", "origin", "deviceSource"
                )
            )
        )
    }

    private fun extractStringValue(obj: JsonObject, keys: List<String>): String? {
        keys.forEach { key ->
            val raw = obj[key]?.jsonPrimitive?.contentOrNull?.trim()
            if (!raw.isNullOrBlank()) return raw
        }
        return null
    }

    private fun firstNotBlank(vararg values: String?): String? {
        values.forEach { value ->
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractNextCursor(root: JsonElement): String? {
        val knownKeys = listOf("nextCursor", "cursor", "nextPageToken", "pageToken", "continuationToken")
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            when (node) {
                is JsonObject -> {
                    knownKeys.forEach { key ->
                        node[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { value ->
                            return value
                        }
                    }
                    node.values.forEach { queue.add(it) }
                }
                is JsonArray -> node.forEach { queue.add(it) }
                else -> Unit
            }
        }
        return null
    }

    private fun containsMeasurementShape(root: JsonElement): Boolean {
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            when (node) {
                is JsonObject -> {
                    val keys = node.keys
                    if (
                        "measurements" in keys ||
                        "measurementLog" in keys ||
                        "foodEntries" in keys ||
                        "insulinEntries" in keys
                    ) {
                        return true
                    }
                    if ("recordNumber" in keys && ("gramsCarbs" in keys || "units" in keys)) {
                        return true
                    }
                    node.values.forEach { queue.add(it) }
                }
                is JsonArray -> node.forEach { queue.add(it) }
                else -> Unit
            }
        }
        return false
    }

    private fun parseTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { value ->
            return if (value > 3_000_000_000L) value else value * 1000L
        }
        return runCatching {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        }.recoverCatching {
            Instant.parse(raw).toEpochMilli()
        }.getOrElse {
            try {
                val fallback = raw.replace(' ', 'T')
                OffsetDateTime.parse(fallback).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun toIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun readEndpointCandidates(): List<ReadEndpointCandidate> {
        return listOf(
            ReadEndpointCandidate(
                id = "get_measurements_iso",
                method = "GET",
                path = "api/measurements",
                queryKeys = listOf("from", "to", "limit", "cursor"),
                expectsCursor = true
            ),
            ReadEndpointCandidate(
                id = "get_measurements_date_range",
                method = "GET",
                path = "api/measurements",
                queryKeys = listOf("startDate", "endDate", "count", "pageToken"),
                expectsCursor = true
            ),
            ReadEndpointCandidate(
                id = "get_measurements_epoch",
                method = "GET",
                path = "api/measurements",
                queryKeys = listOf("fromEpoch", "toEpoch", "limit", "cursor"),
                expectsCursor = true
            ),
            ReadEndpointCandidate(
                id = "post_measurements_query",
                method = "POST",
                path = "api/measurements/query",
                queryKeys = emptyList(),
                expectsCursor = true
            )
        )
    }

    suspend fun fetchConfig(countryCode: String): LibreviewConfigResolved? {
        val normalizedCountry = normalizeCountry(countryCode) ?: return null
        val url = buildConfigUrl(normalizedCountry)
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastErrorMessage = "No se pudo cargar config LibreView ($normalizedCountry): HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string().orEmpty()
                val payload = json.decodeFromString<LibreviewConfigPayload>(body)
                val baseUrl = payload.newYuUrl?.takeIf { it.isNotBlank() } ?: run {
                    lastErrorMessage = "Config LibreView sin newYuUrl ($normalizedCountry)"
                    return null
                }
                lastErrorMessage = null
                LibreviewConfigResolved(
                    countryCode = normalizedCountry,
                    baseUrl = baseUrl,
                    apiKey = payload.newYuApiKey,
                    domain = payload.newYuDomain?.trim()?.ifBlank { null } ?: DEFAULT_DOMAIN,
                    gatewayType = payload.newYuGateway?.trim()?.ifBlank { null } ?: DEFAULT_GATEWAY_TYPE
                )
            }
        }.getOrElse { error ->
            lastErrorMessage = error.message ?: "Error leyendo config LibreView"
            null
        }
    }

    private suspend fun authenticate(
        baseUrl: String,
        apiKey: String?,
        email: String,
        password: String,
        deviceId: String,
        culture: String,
        domain: String,
        gatewayType: String,
        setDevice: Boolean
    ): LibreviewAuthResponse? {
        val requestBody = LibreviewAuthRequest(
            culture = culture,
            deviceId = deviceId,
            password = password,
            setDevice = setDevice,
            userName = email,
            domain = domain,
            gatewayType = gatewayType
        )
        return runCatching {
            val url = "${normalizeBaseUrl(baseUrl)}api/nisperson/getauthentication"
            val jsonBody = json.encodeToString(requestBody)
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept-Language", culture)
                .header("Platform", "Android")
                .header("Version", "2.12.0")
                .header("Abbott-ADC-App-Platform", "Android/14/FSLL/2.12.0")
                .header("GatewayType", gatewayType)
                .apply {
                    if (!apiKey.isNullOrBlank()) {
                        header("x-api-key", apiKey)
                    }
                }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastErrorMessage = "Auth LibreView HTTP ${response.code}: $body"
                    return null
                }
                json.decodeFromString<LibreviewAuthResponse>(body)
            }
        }.getOrElse { error ->
            lastErrorMessage = error.message ?: "Error de autenticación LibreView"
            null
        }
    }

    private fun buildConfigUrl(countryCode: String): String {
        val base = configBaseUrl.trimEnd('/')
        return "$base/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.12_${countryCode}_config.json"
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }

    private fun normalizeCountry(countryCode: String?): String? {
        val normalized = countryCode?.trim()?.uppercase(Locale.ROOT)
        return normalized?.takeIf { it.matches(Regex("^[A-Z]{2}$")) }
    }

    private fun defaultCulture(): String {
        val locale = localeProvider()
        val language = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "US" }
        return "$language-$country"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val DEFAULT_CONFIG_BASE_URL = "https://fsll.freestyleserver.com"
        private const val DEFAULT_DOMAIN = "Libreview"
        private const val LEGACY_DEFAULT_DOMAIN = "LibreView"
        private const val DEFAULT_GATEWAY_TYPE = "FSLibreLink.Android"
        private const val IOS_GATEWAY_TYPE = "FSLibreLink.iOS"
        private const val LIBRE_LINK_UP_ANDROID_GATEWAY_TYPE = "LibreLinkUp.Android"
        private const val LIBRE_LINK_UP_IOS_GATEWAY_TYPE = "LibreLinkUp.iOS"
        private const val STATUS_WRONG_DEVICE_FOR_USER = 20
        private const val STATUS_INVALID_GATEWAY_TYPE = 37
        private const val REASON_WRONG_DEVICE_FOR_USER = "wrongDeviceForUser"
        private const val REASON_WRONG_DEVICE_IN_TOKEN = "wrongDeviceInToken"
        private const val REASON_INVALID_GATEWAY_TYPE = "invalidGatewayType"
        private const val PROBE_LIMIT = 100
        private const val READ_PAGE_LIMIT = 1000
        private const val READ_MAX_PAGES = 40

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
