package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
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

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse(service = "workflow service", status = "UP"))
        }

        get("/capabilities") {
            call.respond(ServiceCapabilities(service = "workflow service", description = CAPABILITIES))
        }
    }
}

@Serializable
data class HealthResponse(val service: String, val status: String)

@Serializable
data class ServiceCapabilities(val service: String, val description: String)

private const val CAPABILITIES = """
Workflow service placeholder service. Full business logic, integrations, and security controls will be implemented incrementally.
"""
