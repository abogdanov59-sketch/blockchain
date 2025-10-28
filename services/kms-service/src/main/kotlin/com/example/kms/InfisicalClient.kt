package com.example.kms

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Thin REST client for Infisical secret management.
 */
class InfisicalClient(
    private val config: InfisicalConfig,
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CachedSecret>()
    private val mutex = Mutex()

    suspend fun readSecret(name: String): String? {
        val cached = cache[name]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.value
        }
        return mutex.withLock {
            val cachedInside = cache[name]
            if (cachedInside != null && cachedInside.expiresAt.isAfter(Instant.now())) {
                return@withLock cachedInside.value
            }
            val response: HttpResponse = httpClient.get {
                url("${config.baseUrl}/api/v3/secrets/raw")
                header("Authorization", "Bearer ${config.serviceToken}")
                parameter("workspaceSlug", config.workspaceSlug)
                parameter("environmentSlug", config.environmentSlug)
                parameter("secretName", name)
            }
            if (response.status == HttpStatusCode.NotFound) {
                cache.remove(name)
                return@withLock null
            }
            response.ensureSuccess("fetch secret $name")
            val payload = response.body<SecretResponse>()
            val secretValue = payload.secret.value
            cache[name] = CachedSecret(secretValue, Instant.now().plusSeconds(config.cacheTtl.inWholeSeconds))
            secretValue
        }
    }

    suspend fun writeSecret(name: String, value: String, metadata: JsonObject? = null) {
        mutex.withLock {
            val payload = SecretWriteRequest(
                workspaceSlug = config.workspaceSlug,
                environmentSlug = config.environmentSlug,
                secretName = name,
                secretValue = value,
                metadata = metadata
            )
            val response: HttpResponse = httpClient.post {
                url("${config.baseUrl}/api/v3/secrets/raw")
                header("Authorization", "Bearer ${config.serviceToken}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            response.ensureSuccess("persist secret $name")
            cache[name] = CachedSecret(value, Instant.now().plusSeconds(config.cacheTtl.inWholeSeconds))
        }
    }

    private suspend fun HttpResponse.ensureSuccess(action: String) {
        if (!status.isSuccess()) {
            val bodyText = runCatching { bodyAsText() }.getOrNull()
            logger.error("Failed to {} via Infisical: status={} body={}", action, status, bodyText)
            error("Unable to $action in Infisical: $status")
        }
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

private data class CachedSecret(val value: String, val expiresAt: Instant)

@Serializable
private data class SecretWriteRequest(
    val workspaceSlug: String,
    val environmentSlug: String,
    val secretName: String,
    val secretValue: String,
    val metadata: JsonObject? = null
)

@Serializable
private data class SecretResponse(
    val secret: SecretPayload
)

@Serializable
private data class SecretPayload(
    @SerialName("secretName") val name: String,
    @SerialName("secretValue") val value: String
)
