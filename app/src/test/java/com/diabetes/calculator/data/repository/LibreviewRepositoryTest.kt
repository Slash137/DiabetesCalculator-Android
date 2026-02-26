package com.diabetes.calculator.data.repository

import com.diabetes.calculator.data.model.LibreviewSession
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreviewRepositoryTest {

    @Test
    fun `resolveCountryCandidates prioriza override y aplica fallback`() {
        val repository = LibreviewRepository(
            localeProvider = { Locale("es", "ES") }
        )

        val countries = repository.resolveCountryCandidates(
            overrideCountry = "gb",
            localeCountry = "ES"
        )

        assertTrue(countries.size >= 3)
        assertEquals("GB", countries[0])
        assertEquals("ES", countries[1])
        assertTrue(countries.contains("US"))
    }

    @Test
    fun `fetchConfigAuto usa region y parsea newYu`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "newYuUrl": "https://api.libreview.test/",
                      "newYuApiKey": "api-key-123"
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val repository = LibreviewRepository(
                configBaseUrl = server.url("/").toString().trimEnd('/')
            )

            val config = repository.fetchConfigAuto(
                overrideCountry = "GB",
                localeCountry = "ES"
            )

            assertNotNull(config)
            assertEquals("GB", config?.countryCode)
            assertEquals("https://api.libreview.test/", config?.baseUrl)
            assertEquals("api-key-123", config?.apiKey)

            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().contains("FSLibreLink_Android_2.12_GB_config.json"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `authenticateWithConfig reintenta con SetDevice true en wrongDeviceForUser`() = runBlocking {
        val server = MockWebServer()
        val authCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().contains("/api/nisperson/getauthentication")) {
                    return if (authCalls.getAndIncrement() == 0) {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"status":20,"reason":"wrongDeviceForUser"}""")
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                {
                                  "status": 0,
                                  "reason": "ok",
                                  "result": {
                                    "UserToken": "token-abc",
                                    "AccountId": "account-1"
                                  }
                                }
                                """.trimIndent()
                            )
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val repository = LibreviewRepository()
            val session = repository.authenticateWithConfig(
                config = LibreviewConfigResolved(
                    countryCode = "US",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    domain = "Libreview",
                    gatewayType = "FSLibreLink.Android"
                ),
                email = "user@test.com",
                password = "secret",
                deviceId = "device-1",
                culture = "en-US"
            )

            assertNotNull("session null, error=${repository.lastErrorMessage}", session)
            assertEquals("token-abc", session?.userToken)
            assertEquals("account-1", session?.accountId)

            val first = server.takeRequest()
            val second = server.takeRequest()
            val firstBody = first.body.readUtf8().replace(" ", "")
            val secondBody = second.body.readUtf8().replace(" ", "")
            assertTrue(firstBody.contains("\"SetDevice\":false"))
            assertTrue(secondBody.contains("\"SetDevice\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `authenticateWithConfig confirma token con SetDevice true tras login ok`() = runBlocking {
        val server = MockWebServer()
        val authCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().contains("/api/nisperson/getauthentication")) {
                    return MockResponse().setResponseCode(404)
                }
                return if (authCalls.getAndIncrement() == 0) {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "status": 0,
                              "reason": "ok",
                              "result": {
                                "UserToken": "token-initial",
                                "AccountId": "account-1"
                              }
                            }
                            """.trimIndent()
                        )
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "status": 0,
                              "reason": "ok",
                              "result": {
                                "UserToken": "token-device-bound",
                                "AccountId": "account-1"
                              }
                            }
                            """.trimIndent()
                        )
                }
            }
        }
        server.start()
        try {
            val repository = LibreviewRepository()
            val session = repository.authenticateWithConfig(
                config = LibreviewConfigResolved(
                    countryCode = "US",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    domain = "Libreview",
                    gatewayType = "FSLibreLink.Android"
                ),
                email = "user@test.com",
                password = "secret",
                deviceId = "device-1",
                culture = "en-US"
            )

            assertNotNull(session)
            assertEquals("token-device-bound", session?.userToken)

            val first = server.takeRequest()
            val second = server.takeRequest()
            val firstBody = first.body.readUtf8().replace(" ", "")
            val secondBody = second.body.readUtf8().replace(" ", "")
            assertTrue(firstBody.contains("\"SetDevice\":false"))
            assertTrue(secondBody.contains("\"SetDevice\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `authenticateWithConfig reintenta gateway cuando recibe invalidGatewayType`() = runBlocking {
        val server = MockWebServer()
        val requestBodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().contains("/api/nisperson/getauthentication")) {
                    return MockResponse().setResponseCode(404)
                }
                val body = request.body.readUtf8()
                requestBodies += body
                return when {
                    body.contains("\"GatewayType\":\"FSLibreLink.Android\"") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"status":37,"reason":"invalidGatewayType"}""")
                    }

                    body.contains("\"GatewayType\":\"FSLibreLink.iOS\"") -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                """
                                {
                                  "status": 0,
                                  "reason": "ok",
                                  "result": {
                                    "UserToken": "token-ios",
                                    "AccountId": "account-ios"
                                  }
                                }
                                """.trimIndent()
                            )
                    }

                    else -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"status":37,"reason":"invalidGatewayType"}""")
                    }
                }
            }
        }
        server.start()
        try {
            val repository = LibreviewRepository()
            val session = repository.authenticateWithConfig(
                config = LibreviewConfigResolved(
                    countryCode = "US",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    domain = "Libreview",
                    gatewayType = "FSLibreLink.Android"
                ),
                email = "user@test.com",
                password = "secret",
                deviceId = "device-1",
                culture = "en-US"
            )

            assertNotNull(session)
            assertEquals("token-ios", session?.userToken)
            assertEquals("FSLibreLink.iOS", session?.gatewayType)
            assertTrue(requestBodies.any { it.contains("FSLibreLink.Android") })
            assertTrue(requestBodies.any { it.contains("FSLibreLink.iOS") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetchMeasurements parsea metadata de dispositivo y serialless`() = runBlocking {
        val server = MockWebServer()
        val body = """
            {
              "measurements": [
                {
                  "recordNumber": 12345,
                  "gramsCarbs": 22.0,
                  "timestamp": "1700031000000",
                  "gatewayType": "FSLibreLink.Android",
                  "extendedProperties": {
                    "factoryTimestamp": "2026-02-22T12:00:00.000Z",
                    "linkedGlucoseRecordNumber": "0",
                    "deviceSerial": "",
                    "deviceId": "app-device-42",
                    "sourceTag": "app-managed"
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        server.start()
        try {
            val repository = LibreviewRepository()
            val entries = repository.fetchMeasurements(
                session = LibreviewSession(
                    userToken = "token",
                    accountId = "account",
                    baseUrl = server.url("/").toString(),
                    apiKey = "api",
                    authenticatedAt = 1_700_031_000_000L
                ),
                fromMillis = 1_700_030_000_000L,
                toMillis = 1_700_040_000_000L
            )

            assertEquals(1, entries.size)
            val entry = entries.first()
            assertEquals("CARBS", entry.channel)
            assertEquals(12345L, entry.recordNumber)
            assertTrue(entry.deviceSerial.isNullOrBlank())
            assertEquals("app-device-42", entry.deviceId)
            assertEquals("FSLibreLink.Android", entry.gatewayType)
            assertEquals("app-managed", entry.sourceTag)
        } finally {
            server.shutdown()
        }
    }
}
