package com.example.kms

import com.example.kmsclient.DeriveSessionKeyRequest
import com.example.kmsclient.InvalidateTmkRequest
import com.example.kmsclient.UnwrapDekRequest
import com.example.kmsclient.WrapDekRequest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import java.time.Instant
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

fun main() {
    val config = KmsConfig()
    val application = KmsApplication(config)
    embeddedServer(Netty, host = config.host, port = config.port) {
        application.configure(this)
    }.start(wait = true)
}

class KmsApplication(private val config: KmsConfig) : AutoCloseable {
    private val dataSource: HikariDataSource = createDataSource(config.database)
    private val database: Database = Database.connect(dataSource)
    private val httpClient: HttpClient = createHttpClient()
    private val infisicalClient = InfisicalClient(config.infisical, httpClient)
    private val pkcs11Module: Pkcs11Module = if (config.pkcs11.enabled) {
        SoftHsmPkcs11Module(config.pkcs11)
    } else {
        InMemoryPkcs11Module()
    }
    private val tmkRepository = TmkRepository()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val tmkService = TmkService(tmkRepository, pkcs11Module, infisicalClient, json)

    init {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(TmkVersionsTable)
        }
    }

    fun configure(application: Application) {
        application.install(CallLogging)
        application.install(ServerContentNegotiation) {
            json(json)
        }
        configureSecurity(application)
        application.routing {
            get("/health") {
                call.respond(mapOf("status" to "UP", "service" to "kms-service", "time" to Instant.now().toString()))
            }
            authenticate("oidc", optional = config.security.devBypassToken != null) {
                route("/keys") {
                    post("/tenants/{tenantId}/tmk") {
                        val tenantId = call.parameters.getOrFail("tenantId")
                        val descriptor = tmkService.issueTenantMasterKey(tenantId)
                        call.respond(descriptor)
                    }
                    post("/wrap-dek") {
                        val body = call.receive<WrapDekRequest>()
                        call.respond(tmkService.wrapDek(body))
                    }
                    post("/unwrap-dek") {
                        val body = call.receive<UnwrapDekRequest>()
                        call.respond(tmkService.unwrapDek(body))
                    }
                    post("/derive/ssk") {
                        val body = call.receive<DeriveSessionKeyRequest>()
                        call.respond(tmkService.deriveSessionKey(body))
                    }
                    get("/{tenantId}/metadata") {
                        val tenantId = call.parameters.getOrFail("tenantId")
                        call.respond(tmkService.listMetadata(tenantId))
                    }
                    post("/{tenantId}/invalidate/{version}") {
                        val tenantId = call.parameters.getOrFail("tenantId")
                        val version = call.parameters.getOrFail("version").toInt()
                        val body = call.receive<InvalidateTmkRequest>()
                        tmkService.markDisabled(tenantId, version, body.status)
                        call.respond(mapOf("status" to "ok"))
                    }
                }
            }
            if (config.security.devBypassToken != null) {
                authenticate("dev") {
                    post("/keys/derive/ssk/dev") {
                        val body = call.receive<DeriveSessionKeyRequest>()
                        call.respond(tmkService.deriveSessionKey(body))
                    }
                }
            }
        }
    }

    private fun configureSecurity(application: Application) {
        application.install(Authentication) {
            val security = config.security
            if (!security.issuer.isNullOrBlank() && !security.jwksUrl.isNullOrBlank()) {
                jwt("oidc") {
                    verifier(JwtVerifierFactory.create(security.jwksUrl!!, security.issuer!!, security.audience))
                    validate { credential ->
                        if (security.audience != null && credential.payload.audience.contains(security.audience)) {
                            JWTPrincipal(credential.payload)
                        } else null
                    }
                }
            } else {
                bearer("oidc") {
                    authenticate { credential ->
                        if (security.devBypassToken != null && credential.token == security.devBypassToken) {
                            UserIdPrincipal("dev")
                        } else null
                    }
                }
            }
            if (security.devBypassToken != null) {
                bearer("dev") {
                    authenticate { credential ->
                        if (credential.token == security.devBypassToken) UserIdPrincipal("dev") else null
                    }
                }
            }
        }
    }

    private fun createHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                private val logger = LoggerFactory.getLogger("InfisicalClient")
                override fun log(message: String) {
                    logger.debug(message)
                }
            }
        }
        install(Auth) {
            bearer {
                loadTokens { BearerTokens(config.infisical.serviceToken, "") }
            }
        }
    }

    private fun createDataSource(db: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = db.jdbcUrl
            username = db.username
            password = db.password
            driverClassName = db.driverClass
            maximumPoolSize = db.maximumPoolSize
            connectionTimeout = db.connectionTimeoutMs
        }
        return HikariDataSource(hikariConfig)
    }

    override fun close() {
        httpClient.close()
        pkcs11Module.close()
        dataSource.close()
    }
}
