package com.example.kmsclient

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.Closeable
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KmsClient(
    private val config: KmsClientConfig,
    private val httpClient: HttpClient = defaultHttpClient(config)
) : Closeable {

    suspend fun health(): KmsHealthResponse = httpClient.get("${config.baseUrl}/health").body()

    suspend fun issueTenantMasterKey(tenantId: String): TmkVersionDescriptor =
        httpClient.post("${config.baseUrl}/keys/tenants/$tenantId/tmk").body()

    suspend fun listTenantMetadata(tenantId: String): List<TmkVersionDescriptor> =
        httpClient.get("${config.baseUrl}/keys/$tenantId/metadata").body()

    suspend fun wrapDek(request: WrapDekRequest): WrappedDekResponse =
        httpClient.post("${config.baseUrl}/keys/wrap-dek") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun unwrapDek(request: UnwrapDekRequest): UnwrappedDekResponse =
        httpClient.post("${config.baseUrl}/keys/unwrap-dek") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun deriveSessionKey(request: DeriveSessionKeyRequest): DeriveSessionKeyResponse =
        httpClient.post("${config.baseUrl}/keys/derive/ssk") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun invalidateTenantMasterKey(
        tenantId: String,
        version: Int,
        request: InvalidateTmkRequest
    ) {
        httpClient.post("${config.baseUrl}/keys/$tenantId/invalidate/$version") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun defaultHttpClient(config: KmsClientConfig): HttpClient = HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) { json(json) }
            install(Logging) { level = LogLevel.NONE }
            config.requestTimeoutMillis?.let { timeoutMillis ->
                install(HttpTimeout) {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
            if (!config.accessToken.isNullOrBlank()) {
                install(Auth) {
                    bearer {
                        sendWithoutRequest { true }
                        loadTokens { BearerTokens(config.accessToken, config.accessToken) }
                    }
                }
            }
        }
    }
}

@Serializable
data class KmsClientConfig(
    val baseUrl: String,
    val accessToken: String? = null,
    val requestTimeoutMillis: Long? = 10_000
)
