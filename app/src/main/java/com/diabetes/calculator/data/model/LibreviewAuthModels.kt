package com.diabetes.calculator.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibreviewConfigPayload(
    @SerialName("newYuUrl")
    val newYuUrl: String? = null,
    @SerialName("newYuApiKey")
    val newYuApiKey: String? = null,
    @SerialName("newYuDomain")
    val newYuDomain: String? = null,
    @SerialName("newYuGateway")
    val newYuGateway: String? = null
)

@Serializable
data class LibreviewAuthRequest(
    @SerialName("Culture")
    val culture: String,
    @SerialName("DeviceId")
    val deviceId: String,
    @SerialName("Password")
    val password: String,
    @SerialName("SetDevice")
    val setDevice: Boolean,
    @SerialName("UserName")
    val userName: String,
    @SerialName("Domain")
    val domain: String = "Libreview",
    @SerialName("GatewayType")
    val gatewayType: String = "FSLibreLink.Android"
)

@Serializable
data class LibreviewAuthResponse(
    val status: Int? = null,
    val reason: String? = null,
    val result: LibreviewAuthResult? = null
)

@Serializable
data class LibreviewAuthResult(
    @SerialName("UserToken")
    val userToken: String? = null,
    @SerialName("AccountId")
    val accountId: String? = null
)

data class LibreviewSession(
    val userToken: String,
    val accountId: String?,
    val baseUrl: String,
    val apiKey: String?,
    val authenticatedAt: Long,
    val countryCode: String? = null,
    val domain: String = "Libreview",
    val gatewayType: String = "FSLibreLink.Android"
)
