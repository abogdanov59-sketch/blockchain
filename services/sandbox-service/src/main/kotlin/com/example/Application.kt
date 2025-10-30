package com.example

import com.example.kmsclient.DeriveSessionKeyRequest
import com.example.kmsclient.KmsClient
import com.example.kmsclient.KmsClientConfig
import com.example.kmsclient.KmsHealthResponse
import com.example.kmsclient.UnwrapDekRequest
import com.example.kmsclient.WrapDekRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs

fun main() {
    io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ServerContentNegotiation) { json() }
    install(CallLogging)

    val config = SandboxServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(config.kms)
    val dataSource = HikariDataSource(config.database.toHikari())
    val database = Database.connect(dataSource)
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(SandboxSessions, SandboxRecords, SandboxProposals)
    }

    val kafkaProducer = KafkaProducer<String, String>(config.kafka.toProperties())
    val minioClient = config.storage.createClient()
    ensureBucket(minioClient, config.storage.bucket)

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(Logging)
    }

    environment.monitor.subscribe(ApplicationStopPreparing) {
        kafkaProducer.close()
        kmsClient.close()
        httpClient.close()
        dataSource.close()
    }

    val repository = SandboxRepository(database, config)
    val dataVaultClient = DataVaultClient(httpClient, config.dataVault)
    val sessionKeyCache = ConcurrentHashMap<UUID, SecretKey>()

    routing {
        get("/health") {
            val dbStatus = runCatching { transaction(database) { SandboxSessions.selectAll().limit(1).count() } }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })
            val kafkaStatus = runCatching { kafkaProducer.partitionsFor(config.kafka.eventsTopic) }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })
            val minioStatus = runCatching { minioClient.listBuckets() }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })

            call.respond(
                HealthResponse(
                    service = "sandbox-service",
                    status = "UP",
                    kms = kmsClient.health(),
                    database = dbStatus,
                    kafka = kafkaStatus,
                    objectStorage = minioStatus,
                    defaultContext = config.defaultContext
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "sandbox-service",
                    description = CAPABILITIES,
                    kmsBaseUrl = config.kms.baseUrl,
                    defaultContext = config.defaultContext,
                    kafkaTopic = config.kafka.eventsTopic,
                    dataVaultUrl = config.dataVault.baseUrl
                )
            )
        }

        route("/sandbox") {
            post("/sessions") {
                val request = call.receive<CreateSandboxRequest>()
                val session = repository.createSession(request, kmsClient, sessionKeyCache)
                val payload = Json.encodeToString(SandboxLifecycleEvent("SESSION_CREATED", session.sessionId, session.tenantId))
                kafkaProducer.send(ProducerRecord(config.kafka.eventsTopic, session.sessionId, payload))
                call.respond(HttpStatusCode.Created, session)
            }

            get("/sessions/{id}") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val session = repository.fetchSession(sessionId)
                call.respond(session ?: HttpStatusCode.NotFound)
            }

            get("/sessions/{id}/records") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val records = repository.listRecords(sessionId)
                call.respond(records)
            }

            post("/sessions/{id}/checkout") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val request = call.receive<CheckoutRequest>()
                val session = repository.fetchSession(sessionId) ?: return@post call.respond(HttpStatusCode.NotFound)
                session.validateActive()?.let { return@post call.respond(HttpStatusCode.Gone, ErrorResponse(it)) }
                val assetEnvelope = dataVaultClient.fetchEnvelope(request.assetId)
                val record = repository.checkoutAsset(session, sessionKeyCache, assetEnvelope, kmsClient)
                val payload = Json.encodeToString(SandboxLifecycleEvent("ASSET_CHECKOUT", session.sessionId, session.tenantId, request.assetId))
                kafkaProducer.send(ProducerRecord(config.kafka.eventsTopic, session.sessionId, payload))
                call.respond(record)
            }

            post("/sessions/{id}/propose") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val request = call.receive<ProposeChangesRequest>()
                val session = repository.fetchSession(sessionId) ?: return@post call.respond(HttpStatusCode.NotFound)
                session.validateActive()?.let { return@post call.respond(HttpStatusCode.Gone, ErrorResponse(it)) }
                val proposal = repository.recordProposal(session, request)
                val payload = Json.encodeToString(SandboxLifecycleEvent("PROPOSAL_SUBMITTED", session.sessionId, session.tenantId, request.assetId))
                kafkaProducer.send(ProducerRecord(config.kafka.eventsTopic, session.sessionId, payload))
                call.respond(HttpStatusCode.Accepted, proposal)
            }

            post("/sessions/{id}/submit") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val session = repository.fetchSession(sessionId) ?: return@post call.respond(HttpStatusCode.NotFound)
                session.validateActive()?.let { return@post call.respond(HttpStatusCode.Gone, ErrorResponse(it)) }
                repository.markSubmitted(sessionId)
                val payload = Json.encodeToString(SandboxLifecycleEvent("SUBMITTED", session.sessionId, session.tenantId))
                kafkaProducer.send(ProducerRecord(config.kafka.eventsTopic, session.sessionId, payload))
                call.respond(HttpStatusCode.Accepted)
            }

            delete("/sessions/{id}") {
                val sessionId = UUID.fromString(call.parameters.getOrFail("id"))
                val session = repository.fetchSession(sessionId) ?: return@delete call.respond(HttpStatusCode.NotFound)
                repository.closeSession(sessionId, minioClient, config.storage.bucket)
                val removed = sessionKeyCache.remove(UUID.fromString(session.sessionId))?.encoded
                removed?.fill(0)
                val payload = Json.encodeToString(SandboxLifecycleEvent("CLOSED", session.sessionId, session.tenantId))
                kafkaProducer.send(ProducerRecord(config.kafka.eventsTopic, session.sessionId, payload))
                call.respond(HttpStatusCode.Accepted)
            }
        }

        route("/kms") {
            get("/health") { call.respond(kmsClient.health()) }
            post("/derive-session-key") {
                val payload = call.receive<DeriveSessionKeyPayload>()
                val request = DeriveSessionKeyRequest(
                    tenantId = payload.tenantId,
                    sessionNonce = payload.sessionNonce,
                    context = payload.context ?: config.defaultContext,
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

private fun ensureBucket(client: MinioClient, bucket: String) {
    val exists = runCatching { client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()) }.getOrNull() ?: false
    if (!exists) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
    }
}

private fun SandboxSessionDto.validateActive(): String? {
    if (status != "ACTIVE") {
        return "Session $sessionId is not active ($status)"
    }
    if (Instant.parse(expiresAt).isBefore(Instant.now())) {
        return "Session $sessionId expired at $expiresAt"
    }
    return null
}

class SandboxRepository(private val database: Database, private val config: SandboxServiceConfig) {
    suspend fun createSession(
        request: CreateSandboxRequest,
        kmsClient: KmsClient,
        sessionCache: ConcurrentHashMap<UUID, SecretKey>
    ): SandboxSessionDto = withContext(Dispatchers.IO) {
        val nonce = CryptoToolkit.generateNonce()
        val sessionNonce = CryptoToolkit.base64Encode(nonce)
        val deriveRequest = DeriveSessionKeyRequest(
            tenantId = request.tenantId,
            sessionNonce = sessionNonce,
            context = request.context ?: config.defaultContext,
            tmkVersion = request.tmkVersion
        )
        val derived = kmsClient.deriveSessionKey(deriveRequest)
        val keyBytes = CryptoToolkit.base64Decode(derived.sessionKey)
        val sessionKey = SecretKeySpec(keyBytes, "AES")
        val sessionId = UUID.randomUUID()
        val now = Instant.now()
        val ttl = request.ttlMinutes ?: config.defaultTtlMinutes
        val expiresAt = now.plus(Duration.ofMinutes(ttl.toLong()))

        transaction(database) {
            SandboxSessions.insert { row ->
                row[id] = sessionId
                row[tenantId] = request.tenantId
                row[context] = request.context ?: config.defaultContext
                row[status] = "ACTIVE"
                row[nonceField] = sessionNonce
                row[createdAt] = now
                row[expiresAtField] = expiresAt
            }
        }

        sessionCache[sessionId] = sessionKey

        SandboxSessionDto(
            sessionId = sessionId.toString(),
            tenantId = request.tenantId,
            context = request.context ?: config.defaultContext,
            nonce = sessionNonce,
            status = "ACTIVE",
            createdAt = now.toString(),
            expiresAt = expiresAt.toString()
        )
    }

    fun fetchSession(sessionId: UUID): SandboxSessionDto? = transaction(database) {
        SandboxSessions.select { SandboxSessions.id eq sessionId }.singleOrNull()?.let {
            SandboxSessionDto(
                sessionId = it[SandboxSessions.id].value.toString(),
                tenantId = it[SandboxSessions.tenantId],
                context = it[SandboxSessions.context],
                nonce = it[SandboxSessions.nonceField],
                status = it[SandboxSessions.status],
                createdAt = it[SandboxSessions.createdAt].toString(),
                expiresAt = it[SandboxSessions.expiresAtField].toString()
            )
        }
    }

    fun listRecords(sessionId: UUID): List<SandboxRecordDto> = transaction(database) {
        SandboxRecords.select { SandboxRecords.sessionId eq sessionId }.map {
            SandboxRecordDto(
                assetId = it[SandboxRecords.assetId].toString(),
                sandboxCiphertext = it[SandboxRecords.ciphertext],
                sandboxTag = it[SandboxRecords.tag],
                sandboxNonce = it[SandboxRecords.nonce],
                aad = it[SandboxRecords.aad],
                createdAt = it[SandboxRecords.createdAt].toString()
            )
        }
    }

    suspend fun checkoutAsset(
        session: SandboxSessionDto,
        sessionCache: ConcurrentHashMap<UUID, SecretKey>,
        envelope: VaultEnvelopeDto,
        kmsClient: KmsClient
    ): SandboxRecordDto = withContext(Dispatchers.IO) {
        val sessionId = UUID.fromString(session.sessionId)
        val sessionKey = sessionCache[sessionId] ?: throw IllegalStateException("Session key missing")
        val dekResponse = kmsClient.unwrapDek(
            UnwrapDekRequest(
                tenantId = envelope.tenantId,
                wrappedDek = envelope.wrappedDek,
                tmkVersion = envelope.tmkVersion
            )
        )
        val dek = SecretKeySpec(CryptoToolkit.base64Decode(dekResponse.dekPlain), "AES")
        val envelopePayload = EnvelopePayload(
            nonce = CryptoToolkit.base64Decode(envelope.nonce),
            ciphertext = CryptoToolkit.base64Decode(envelope.ciphertext),
            tag = CryptoToolkit.base64Decode(envelope.tag),
            aad = CryptoToolkit.base64Decode(envelope.aad)
        )
        val plaintext = CryptoToolkit.aesGcmDecrypt(envelopePayload, dek)
        val sandboxEnvelope = CryptoToolkit.aesGcmEncrypt(plaintext, envelopePayload.aad, sessionKey)
        val recordId = UUID.randomUUID()
        val createdAt = Instant.now()
        transaction(database) {
            SandboxRecords.insert { row ->
                row[id] = recordId
                row[sessionId] = sessionId
                row[assetId] = UUID.fromString(envelope.assetId)
                row[aad] = CryptoToolkit.base64Encode(sandboxEnvelope.aad)
                row[ciphertext] = CryptoToolkit.base64Encode(sandboxEnvelope.ciphertext)
                row[tag] = CryptoToolkit.base64Encode(sandboxEnvelope.tag)
                row[nonce] = CryptoToolkit.base64Encode(sandboxEnvelope.nonce)
                row[createdAt] = createdAt
            }
        }
        SandboxRecordDto(
            assetId = envelope.assetId,
            sandboxCiphertext = CryptoToolkit.base64Encode(sandboxEnvelope.ciphertext),
            sandboxTag = CryptoToolkit.base64Encode(sandboxEnvelope.tag),
            sandboxNonce = CryptoToolkit.base64Encode(sandboxEnvelope.nonce),
            aad = CryptoToolkit.base64Encode(sandboxEnvelope.aad),
            createdAt = createdAt.toString()
        )
    }

    fun recordProposal(session: SandboxSessionDto, request: ProposeChangesRequest): SandboxProposalDto {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = CryptoToolkit.base64Encode(digest.digest(request.diff.toByteArray(StandardCharsets.UTF_8)))
        val now = Instant.now()
        val proposal = SandboxProposalDto(
            sessionId = session.sessionId,
            assetId = request.assetId,
            diffHash = hash,
            comment = request.comment,
            submittedAt = now.toString()
        )
        transaction(database) {
            SandboxProposals.insert { row ->
                row[id] = UUID.randomUUID()
                row[sessionRef] = UUID.fromString(session.sessionId)
                row[assetId] = UUID.fromString(request.assetId)
                row[diffHash] = hash
                row[comment] = request.comment
                row[createdAt] = now
            }
        }
        return proposal
    }

    fun markSubmitted(sessionId: UUID) {
        transaction(database) {
            SandboxSessions.update({ SandboxSessions.id eq sessionId }) {
                it[status] = "SUBMITTED"
            }
        }
    }

    fun closeSession(sessionId: UUID, minioClient: MinioClient, bucket: String) {
        transaction(database) {
            SandboxSessions.update({ SandboxSessions.id eq sessionId }) {
                it[status] = "CLOSED"
            }
            SandboxRecords.select { SandboxRecords.sessionId eq sessionId }.forEach { record ->
                val objectName = "${sessionId}/${record[SandboxRecords.assetId]}"
                runCatching {
                    minioClient.removeObject(
                        RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .`object`(objectName)
                            .build()
                    )
                }
            }
            SandboxRecords.deleteWhere { SandboxRecords.sessionId eq sessionId }
            SandboxProposals.deleteWhere { SandboxProposals.sessionRef eq sessionId }
        }
    }
}

class DataVaultClient(private val httpClient: HttpClient, private val config: DataVaultClientConfig) {
    suspend fun fetchEnvelope(assetId: String): VaultEnvelopeDto {
        val response = httpClient.get("${config.baseUrl}/vault/assets/$assetId/envelope")
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw IllegalStateException("Failed to load asset envelope: ${response.status} $body")
        }
        return response.body()
    }
}

object SandboxSessions : UUIDTable("sandbox_sessions") {
    val tenantId = varchar("tenant_id", 64)
    val context = varchar("context", 64)
    val status = varchar("status", 32)
    val nonceField = text("nonce")
    val createdAt = timestamp("created_at")
    val expiresAtField = timestamp("expires_at")
}

object SandboxRecords : UUIDTable("sandbox_records") {
    val sessionId = reference("session_id", SandboxSessions)
    val assetId = uuid("asset_id")
    val aad = text("aad")
    val ciphertext = text("ciphertext")
    val tag = text("tag")
    val nonce = text("nonce")
    val createdAt = timestamp("created_at")
}

object SandboxProposals : UUIDTable("sandbox_proposals") {
    val sessionRef = reference("session_id", SandboxSessions)
    val assetId = uuid("asset_id")
    val diffHash = text("diff_hash")
    val comment = text("comment").nullable()
    val createdAt = timestamp("created_at")
}

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse,
    val database: String,
    val kafka: String,
    val objectStorage: String,
    val defaultContext: String
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String,
    val defaultContext: String,
    val kafkaTopic: String,
    val dataVaultUrl: String
)

@Serializable
data class CreateSandboxRequest(
    val tenantId: String,
    val context: String? = null,
    val ttlMinutes: Int? = null,
    val tmkVersion: Int? = null
)

@Serializable
data class SandboxSessionDto(
    val sessionId: String,
    val tenantId: String,
    val context: String,
    val nonce: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String
)

@Serializable
data class CheckoutRequest(val assetId: String)

@Serializable
data class SandboxRecordDto(
    val assetId: String,
    val sandboxCiphertext: String,
    val sandboxTag: String,
    val sandboxNonce: String,
    val aad: String,
    val createdAt: String
)

@Serializable
data class ProposeChangesRequest(
    val assetId: String,
    val diff: String,
    val comment: String? = null
)

@Serializable
data class SandboxProposalDto(
    val sessionId: String,
    val assetId: String,
    val diffHash: String,
    val comment: String?,
    val submittedAt: String
)

@Serializable
data class SandboxLifecycleEvent(
    val type: String,
    val sessionId: String,
    val tenantId: String,
    val assetId: String? = null
)

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class DeriveSessionKeyPayload(
    val tenantId: String,
    val sessionNonce: String,
    val context: String? = null,
    val tmkVersion: Int? = null
)

@Serializable
data class SandboxServiceConfig(
    val kms: KmsClientConfig,
    val database: DatabaseConfig,
    val kafka: KafkaConfig,
    val storage: StorageConfig,
    val dataVault: DataVaultClientConfig,
    val defaultContext: String,
    val defaultTtlMinutes: Int
) {
    companion object {
        private const val DEFAULT_CONTEXT = "sandbox"
        private const val DEFAULT_TTL_MINUTES = 120

        fun fromEnvironment(): SandboxServiceConfig {
            val kmsBaseUrl = System.getenv("KMS_BASE_URL") ?: "http://localhost:8088"
            val accessToken = System.getenv("KMS_ACCESS_TOKEN")
            val timeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: 10000L

            val jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/platform"
            val jdbcUser = System.getenv("JDBC_USERNAME") ?: "platform"
            val jdbcPassword = System.getenv("JDBC_PASSWORD") ?: "platform"

            val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "kafka:9092"
            val kafkaTopic = System.getenv("KAFKA_SANDBOX_TOPIC") ?: "sandbox.events"

            val endpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
            val accessKey = System.getenv("MINIO_ACCESS_KEY") ?: "minioadmin"
            val secretKey = System.getenv("MINIO_SECRET_KEY") ?: "minioadmin"
            val bucket = System.getenv("MINIO_SANDBOX_BUCKET") ?: "sandbox-assets"

            val dataVaultUrl = System.getenv("DATA_VAULT_BASE_URL") ?: "http://data-vault-service:8080"
            val defaultContext = System.getenv("SANDBOX_DEFAULT_CONTEXT") ?: DEFAULT_CONTEXT
            val ttlMinutes = System.getenv("SANDBOX_DEFAULT_TTL")?.toIntOrNull() ?: DEFAULT_TTL_MINUTES

            return SandboxServiceConfig(
                kms = KmsClientConfig(baseUrl = kmsBaseUrl, accessToken = accessToken, requestTimeoutMillis = timeout),
                database = DatabaseConfig(jdbcUrl, jdbcUser, jdbcPassword),
                kafka = KafkaConfig(kafkaBootstrap, kafkaTopic),
                storage = StorageConfig(endpoint, accessKey, secretKey, bucket),
                dataVault = DataVaultClientConfig(dataVaultUrl),
                defaultContext = defaultContext,
                defaultTtlMinutes = ttlMinutes
            )
        }
    }
}

@Serializable
data class DataVaultClientConfig(val baseUrl: String)

@Serializable
data class DatabaseConfig(val jdbcUrl: String, val username: String, val password: String) {
    fun toHikari(): HikariConfig = HikariConfig().apply {
        jdbcUrl = this@DatabaseConfig.jdbcUrl
        username = this@DatabaseConfig.username
        password = this@DatabaseConfig.password
        maximumPoolSize = 5
        driverClassName = "org.postgresql.Driver"
    }
}

@Serializable
data class KafkaConfig(val bootstrapServers: String, val eventsTopic: String) {
    fun toProperties(): Properties = Properties().apply {
        put("bootstrap.servers", bootstrapServers)
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("acks", "all")
    }
}

@Serializable
data class StorageConfig(val endpoint: String, val accessKey: String, val secretKey: String, val bucket: String) {
    fun createClient(): MinioClient = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build()
}

@Serializable
data class VaultEnvelopeDto(
    val cipherAlgo: String,
    val nonce: String,
    val aad: String,
    val ciphertext: String,
    val tag: String,
    val wrappedDek: String,
    val dekAlgo: String,
    val tmkId: String,
    val tmkVersion: Int,
    val tenantId: String,
    val assetId: String
)

private const val CAPABILITIES = """
Sandbox service derives SSKs from the KMS, hydrates envelopes from the data vault, re-encrypts them under session keys, persists metadata in PostgreSQL and emits lifecycle events to Kafka.
"""
