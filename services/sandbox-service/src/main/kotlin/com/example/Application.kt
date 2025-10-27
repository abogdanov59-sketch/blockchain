package com.example

import com.example.kmsclient.DeriveSessionKeyRequest
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

    val serviceConfig = SandboxServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(serviceConfig.kms)

    environment.monitor.subscribe(ApplicationStop) {
        kmsClient.close()
    }

    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    service = "sandbox service",
                    status = "UP",
                    kms = kmsClient.health(),
                    defaultContext = serviceConfig.defaultContext
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "sandbox service",
                    description = CAPABILITIES,
                    kmsBaseUrl = serviceConfig.kms.baseUrl,
                    defaultContext = serviceConfig.defaultContext
                )
            )
        }

        route("/kms") {
            get("/health") {
                call.respond(kmsClient.health())
            }

            post("/derive-session-key") {
                val payload = call.receive<DeriveSessionKeyPayload>()
                val request = DeriveSessionKeyRequest(
                    tenantId = payload.tenantId,
                    sessionNonce = payload.sessionNonce,
                    context = payload.context ?: serviceConfig.defaultContext,
                    tmkVersion = payload.tmkVersion
                )
                call.respond(kmsClient.deriveSessionKey(request))
            }

            post("/wrap-dek") {
                val request = call.receive<WrapDekRequest>()
                call.respond(kmsClient.wrapDek(request))
            }

            post("/unwrap-dek") {
                val request = call.receive<UnwrapDekRequest>()
                call.respond(kmsClient.unwrapDek(request))
            }
        }
    }
}

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse,
    val defaultContext: String
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String,
    val defaultContext: String
)

@Serializable
data class SandboxServiceConfig(
    val kms: KmsClientConfig,
    val defaultContext: String
) {
    companion object {
        private const val DEFAULT_KMS_URL = "http://localhost:8080"
        private const val DEFAULT_CONTEXT = "sandbox"
        private const val DEFAULT_TIMEOUT = 10000L

        fun fromEnvironment(): SandboxServiceConfig {
            val baseUrl = System.getenv("KMS_BASE_URL") ?: DEFAULT_KMS_URL
            val token = System.getenv("KMS_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
            val timeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_TIMEOUT
            val context = System.getenv("SANDBOX_DEFAULT_CONTEXT")?.takeIf { it.isNotBlank() } ?: DEFAULT_CONTEXT
            return SandboxServiceConfig(
                kms = KmsClientConfig(baseUrl = baseUrl, accessToken = token, requestTimeoutMillis = timeout),
                defaultContext = context
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
Sandbox service now consumes the central KMS for deriving sandbox session keys, wrapping tenant data keys, and managing context-specific encryption material.
"""
