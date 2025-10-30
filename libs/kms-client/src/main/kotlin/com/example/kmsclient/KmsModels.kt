package com.example.kmsclient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TmkStatus {
    @SerialName("ACTIVE")
    ACTIVE,

    @SerialName("PENDING_ROTATION")
    PENDING_ROTATION,

    @SerialName("DISABLED")
    DISABLED,

    @SerialName("COMPROMISED")
    COMPROMISED
}

@Serializable
data class TmkVersionDescriptor(
    val tenantId: String,
    val tmkId: String,
    val version: Int,
    val status: TmkStatus,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class TmkSecretPayload(
    val tenantId: String,
    val tmkId: String,
    val version: Int,
    val wrappedKey: String,
    val algorithm: String,
    val createdAt: String
)

@Serializable
data class WrapDekRequest(
    val tenantId: String,
    val dekPlain: String,
    val tmkVersion: Int? = null
)

@Serializable
data class WrappedDekResponse(
    val tenantId: String,
    val tmkId: String,
    val tmkVersion: Int,
    val wrappedDek: String,
    val dekAlgo: String,
    val tmkAlgo: String
)

@Serializable
data class UnwrapDekRequest(
    val tenantId: String,
    val wrappedDek: String,
    val tmkVersion: Int? = null
)

@Serializable
data class UnwrappedDekResponse(
    val tenantId: String,
    val tmkId: String,
    val tmkVersion: Int,
    val dekPlain: String
)

@Serializable
data class DeriveSessionKeyRequest(
    val tenantId: String,
    val sessionNonce: String,
    val context: String,
    val tmkVersion: Int? = null
)

@Serializable
data class DeriveSessionKeyResponse(
    val tenantId: String,
    val tmkId: String,
    val tmkVersion: Int,
    val sessionKey: String
)

@Serializable
data class InvalidateTmkRequest(
    val status: TmkStatus
)

@Serializable
data class KmsHealthResponse(
    val status: String,
    val service: String? = null,
    val time: String? = null
)
