package com.example

import com.example.kmsclient.InvalidateTmkRequest
import com.example.kmsclient.KmsClient
import com.example.kmsclient.KmsClientConfig
import com.example.kmsclient.KmsHealthResponse
import com.example.kmsclient.TmkVersionDescriptor
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
import io.ktor.server.util.getOrFail
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

    val serviceConfig = WorkflowServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(serviceConfig.kms)

    environment.monitor.subscribe(ApplicationStop) {
        kmsClient.close()
    }

    routing {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    service = "workflow service",
                    status = "UP",
                    kms = kmsClient.health()
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "workflow service",
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
                val metadata: List<TmkVersionDescriptor> = kmsClient.listTenantMetadata(tenantId)
                call.respond(metadata)
            }

            post("/tenants/{tenantId}/tmk") {
                val tenantId = call.parameters.getOrFail("tenantId")
                call.respond(kmsClient.issueTenantMasterKey(tenantId))
            }

            post("/tenants/{tenantId}/invalidate/{version}") {
                val tenantId = call.parameters.getOrFail("tenantId")
                val version = call.parameters.getOrFail("version").toInt()
                val request = call.receive<InvalidateTmkRequest>()
                kmsClient.invalidateTenantMasterKey(tenantId, version, request)
                call.respond(HttpStatusCode.Accepted)
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
data class WorkflowServiceConfig(
    val kms: KmsClientConfig
) {
    companion object {
        private const val DEFAULT_KMS_URL = "http://localhost:8080"
        private const val DEFAULT_TIMEOUT = 10000L

        fun fromEnvironment(): WorkflowServiceConfig {
            val baseUrl = System.getenv("KMS_BASE_URL") ?: DEFAULT_KMS_URL
            val token = System.getenv("KMS_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
            val timeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: DEFAULT_TIMEOUT
            return WorkflowServiceConfig(
                kms = KmsClientConfig(baseUrl = baseUrl, accessToken = token, requestTimeoutMillis = timeout)
            )
        }
    }
}

private const val CAPABILITIES = """
Workflow service integrates with the KMS to orchestrate tenant master key issuance, metadata inspection, and lifecycle state transitions across BPMN workflows.
"""
