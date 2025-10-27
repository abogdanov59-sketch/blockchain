package com.example

import com.example.kmsclient.KmsClient
import com.example.kmsclient.KmsClientConfig
import com.example.kmsclient.KmsHealthResponse
import com.example.kmsclient.UnwrapDekRequest
import com.example.kmsclient.WrapDekRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStop
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val serviceConfig = DataVaultServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(serviceConfig.kms)

    environment.monitor.subscribe(ApplicationStop) {
        kmsClient.close()
    }

    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    service = "data vault service",
                    status = "UP",
                    kms = kmsClient.health()
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "data vault service",
                    description = CAPABILITIES,
                    kmsBaseUrl = serviceConfig.kms.baseUrl
                )
            )
        }

        route("/kms") {
            get("/health") {
                call.respond(kmsClient.health())
            }

            get("/tenants/{tenantId}/metadata") {
                val tenantId = call.parameters.getOrFail("tenantId")
                call.respond(kmsClient.listTenantMetadata(tenantId))
            }

            post("/wrap-dek") {
                val request = call.receive<WrapDekRequest>()
                call.respond(kmsClient.wrapDek(request))
            }

            post("/unwrap-dek") {
                val request = call.receive<UnwrapDekRequest>()
                call.respond(kmsClient.unwrapDek(request))
            }

            post("/derive-session-key") {
                val request = call.receive<DeriveSessionKeyPayload>()
                val kmsRequest = com.example.kmsclient.DeriveSessionKeyRequest(
                    tenantId = request.tenantId,
                    sessionNonce = request.sessionNonce,
                    context = request.context ?: serviceConfig.sessionContext,
                    tmkVersion = request.tmkVersion
                )
                call.respond(kmsClient.deriveSessionKey(kmsRequest))
            }
        }
    }
}

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String
)

@Serializable
data class DataVaultServiceConfig(
    val kms: KmsClientConfig,
    val sessionContext: String
) {
    companion object {
        private const val DEFAULT_KMS_URL = "http://localhost:8080"
        private const val DEFAULT_TIMEOUT = 10000L

        fun fromEnvironment(): DataVaultServiceConfig {
            val baseUrl = System.getenv("KMS_BASE_URL") ?: DEFAULT_KMS_URL
            val token = System.getenv("KMS_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
            val timeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_TIMEOUT
            val sessionContext = System.getenv("SANDBOX_SESSION_CONTEXT") ?: "data-vault"
            return DataVaultServiceConfig(
                kms = KmsClientConfig(baseUrl = baseUrl, accessToken = token, requestTimeoutMillis = timeout),
                sessionContext = sessionContext
            )
        }
    }
}

@Serializable
data class DeriveSessionKeyPayload(
    val tenantId: String,
    val sessionNonce: String,
    val context: String? = null,
    val tmkVersion: Int? = null
)

private const val CAPABILITIES = """
Data vault service integrates with the KMS for tenant master key orchestration, envelope encryption helpers and sandbox session key derivation.
"""
