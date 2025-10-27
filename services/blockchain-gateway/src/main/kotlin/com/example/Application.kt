package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }

    val anchors = ConcurrentLinkedQueue<AuditAnchor>()

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(service = "blockchain gateway", status = "UP"))
        }

        get("/capabilities") {
            call.respond(ServiceCapabilities(service = "blockchain gateway", description = CAPABILITIES))
        }

        post("/audit/anchors") {
            val request = call.receive<AuditAnchorRequest>()
            val anchor = AuditAnchor(
                eventId = request.eventId,
                hash = request.hash,
                createdAt = request.createdAt ?: Instant.now().toString()
            )
            anchors.add(anchor)
            call.respond(HttpStatusCode.Accepted, anchor)
        }

        get("/audit/anchors") {
            call.respond(anchors.toList())
        }
    }
}

@Serializable
data class HealthResponse(val service: String, val status: String)

@Serializable
data class ServiceCapabilities(val service: String, val description: String)

@Serializable
data class AuditAnchorRequest(val eventId: String, val hash: String, val createdAt: String? = null)

@Serializable
data class AuditAnchor(val eventId: String, val hash: String, val createdAt: String)

private const val CAPABILITIES = """
Blockchain gateway anchors hash commitments submitted by upstream services and exposes them for verification.
"""
