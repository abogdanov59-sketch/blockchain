package com.example.kms

import com.example.CryptoToolkit
import com.example.kmsclient.DeriveSessionKeyRequest
import com.example.kmsclient.DeriveSessionKeyResponse
import com.example.kmsclient.InvalidateTmkRequest
import com.example.kmsclient.TmkSecretPayload
import com.example.kmsclient.TmkStatus
import com.example.kmsclient.TmkVersionDescriptor
import com.example.kmsclient.UnwrapDekRequest
import com.example.kmsclient.UnwrappedDekResponse
import com.example.kmsclient.WrapDekRequest
import com.example.kmsclient.WrappedDekResponse
import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class TmkService(
    private val repository: TmkRepository,
    private val pkcs11Module: Pkcs11Module,
    private val infisicalClient: InfisicalClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun issueTenantMasterKey(tenantId: String): TmkVersionDescriptor {
        val tmkId = tmkId(tenantId)
        val previousActive = repository.findActive(tenantId)
        val newKey = CryptoToolkit.generateAesKey()
        val wrapped = pkcs11Module.wrapUnderRootKey(newKey)
        val version = repository.createVersion(tenantId, tmkId, TmkStatus.ACTIVE)
        val secretPayload = TmkSecretPayload(
            tenantId = tenantId,
            tmkId = tmkId,
            version = version.version,
            wrappedKey = CryptoToolkit.base64Encode(wrapped),
            algorithm = "AES-256",
            createdAt = Instant.now().toString()
        )
        infisicalClient.writeSecret(secretName(tenantId, version.version), json.encodeToString(secretPayload))
        previousActive?.let { active ->
            repository.markStatus(active.tenantId, active.version, TmkStatus.PENDING_ROTATION)
        }
        newKey.encoded?.let(CryptoToolkit::zeroize)
        return version.toDescriptor()
    }

    suspend fun wrapDek(request: WrapDekRequest): WrappedDekResponse {
        val (metadata, tmkKey) = loadTmk(request.tenantId, request.tmkVersion)
        val dekBytes = CryptoToolkit.base64Decode(request.dekPlain)
        val dekKey = SecretKeySpec(dekBytes, "AES")
        val wrappedDek = CryptoToolkit.wrapAesKey(dekKey, tmkKey)
        CryptoToolkit.zeroize(dekBytes)
        return WrappedDekResponse(
            tenantId = request.tenantId,
            tmkId = metadata.tmkId,
            tmkVersion = metadata.version,
            wrappedDek = CryptoToolkit.base64Encode(wrappedDek),
            dekAlgo = "AES-256",
            tmkAlgo = "AES-256"
        )
    }

    suspend fun unwrapDek(request: UnwrapDekRequest): UnwrappedDekResponse {
        val (metadata, tmkKey) = loadTmk(request.tenantId, request.tmkVersion)
        val wrappedDek = CryptoToolkit.base64Decode(request.wrappedDek)
        val dekKey = CryptoToolkit.unwrapAesKey(wrappedDek, tmkKey)
        CryptoToolkit.zeroize(wrappedDek)
        return UnwrappedDekResponse(
            tenantId = request.tenantId,
            tmkId = metadata.tmkId,
            tmkVersion = metadata.version,
            dekPlain = CryptoToolkit.base64Encode(dekKey.encoded)
        )
    }

    suspend fun deriveSessionKey(request: DeriveSessionKeyRequest): DeriveSessionKeyResponse {
        val (metadata, tmkKey) = loadTmk(request.tenantId, request.tmkVersion)
        val tmkBytes = tmkKey.encoded ?: error("TMK material is not exportable; derivation requires exportable TMK")
        val nonce = CryptoToolkit.base64Decode(request.sessionNonce)
        val derivedKey = CryptoToolkit.deriveSessionKey(tmkBytes, nonce, request.context)
        CryptoToolkit.zeroize(tmkBytes)
        CryptoToolkit.zeroize(nonce)
        val derivedBytes = derivedKey.encoded
        val encoded = CryptoToolkit.base64Encode(derivedBytes)
        CryptoToolkit.zeroize(derivedBytes)
        return DeriveSessionKeyResponse(
            tenantId = request.tenantId,
            tmkId = metadata.tmkId,
            tmkVersion = metadata.version,
            sessionKey = encoded
        )
    }

    fun listMetadata(tenantId: String): List<TmkVersionDescriptor> = repository.listVersions(tenantId).map { it.toDescriptor() }

    fun markDisabled(tenantId: String, version: Int, status: TmkStatus) {
        require(status == TmkStatus.DISABLED || status == TmkStatus.COMPROMISED) {
            "Only DISABLED or COMPROMISED states can be set via invalidate"
        }
        repository.markStatus(tenantId, version, status)
    }

    private suspend fun loadTmk(tenantId: String, requestedVersion: Int?): Pair<TmkVersion, SecretKey> {
        val version = requestedVersion?.let { repository.findVersion(tenantId, it) }
            ?: repository.findActive(tenantId)
            ?: error("No TMK available for tenant $tenantId")
        val secretName = secretName(tenantId, version.version)
        val secretJson = infisicalClient.readSecret(secretName)
            ?: error("Wrapped TMK not found in Infisical for $secretName")
        val payload = json.decodeFromString<TmkSecretPayload>(secretJson)
        val wrapped = CryptoToolkit.base64Decode(payload.wrappedKey)
        val tmkKey = pkcs11Module.unwrapUnderRootKey(wrapped)
        CryptoToolkit.zeroize(wrapped)
        return version to tmkKey
    }

    private fun secretName(tenantId: String, version: Int): String = "tmk/${tenantId}/v${version}"

    private fun tmkId(tenantId: String): String = "${tenantId}-tmk"

    private fun TmkVersion.toDescriptor(): TmkVersionDescriptor = TmkVersionDescriptor(
        tenantId = tenantId,
        tmkId = tmkId,
        version = version,
        status = status,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )
}
