package com.example

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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.server.application.install
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.orderBy
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ServerContentNegotiation) { json() }
    install(CallLogging)

    val config = AuditServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(config.kms)
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(Logging)
    }
    val blockchainClient = BlockchainGatewayClient(httpClient, config.blockchain)

    val dataSource = HikariDataSource(config.database.toHikari())
    val database = Database.connect(dataSource)
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(AuditEvents, AuditAnchors)
        if (AuditAnchors.selectAll().empty()) {
            AuditAnchors.insert { row ->
                row[id] = UUID.randomUUID()
                row[lastHash] = config.blockchain.initialHash
                row[updatedAt] = Instant.now()
            }
        }
    }

    val kafkaProducer = KafkaProducer<String, String>(config.kafka.toProperties())

    environment.monitor.subscribe(ApplicationStopPreparing) {
        kafkaProducer.close()
        httpClient.close()
        kmsClient.close()
        dataSource.close()
    }

    val repository = AuditRepository(database)

    routing {
        get("/health") {
            val dbStatus = runCatching { transaction(database) { AuditEvents.selectAll().limit(1).toList().size } }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })
            val kafkaStatus = runCatching { kafkaProducer.partitionsFor(config.kafka.topic) }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })
            val gatewayStatus = runCatching { blockchainClient.health() }
                .fold(onSuccess = { it }, onFailure = { "DOWN: ${it.message}" })
            call.respond(
                HealthResponse(
                    service = "audit-service",
                    status = "UP",
                    kms = kmsClient.health(),
                    database = dbStatus,
                    kafka = kafkaStatus,
                    blockchainGateway = gatewayStatus
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "audit-service",
                    description = CAPABILITIES,
                    kmsBaseUrl = config.kms.baseUrl,
                    kafkaTopic = config.kafka.topic,
                    blockchainGatewayUrl = config.blockchain.baseUrl
                )
            )
        }

        route("/audit") {
            post("/events") {
                val request = call.receive<AppendAuditEventRequest>()
                val event = repository.appendEvent(request)
                val eventPayload = Json.encodeToString(event)
                kafkaProducer.send(ProducerRecord(config.kafka.topic, event.eventId, eventPayload))
                blockchainClient.publishAnchor(AuditAnchorRequest(event.eventId, event.chainHash, event.createdAt))
                call.respond(HttpStatusCode.Created, event)
            }

            get("/events/{assetId}") {
                val assetId = call.parameters.getOrFail("assetId")
                val events = repository.listEventsByAsset(assetId)
                call.respond(events)
            }

            get("/chain/head") {
                call.respond(repository.chainHead())
            }
        }

        route("/kms") {
            get("/health") { call.respond(kmsClient.health()) }
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
        }
    }
}

class AuditRepository(private val database: Database) {
    suspend fun appendEvent(request: AppendAuditEventRequest): AuditEventResponse = withContext(Dispatchers.IO) {
        val payloadBytes = request.payload.toByteArray(StandardCharsets.UTF_8)
        val payloadHash = CryptoToolkit.base64Encode(MessageDigest.getInstance("SHA-256").digest(payloadBytes))
        val now = Instant.now()

        transaction(database) {
            val anchorRow = AuditAnchors.selectAll().limit(1).first()
            val previousRow = AuditEvents.selectAll().orderBy(AuditEvents.position to SortOrder.DESC).limit(1).firstOrNull()
            val previousHash = previousRow?.get(AuditEvents.chainHash) ?: anchorRow[AuditAnchors.lastHash]
            val previousIndex = previousRow?.get(AuditEvents.position) ?: 0
            val entryHash = chainHash(previousHash, payloadHash, now)
            val metadataJson = Json.encodeToString(MapSerializer, request.metadata)
            val eventId = UUID.randomUUID()
            AuditEvents.insert { row ->
                row[id] = eventId
                row[tenantId] = request.tenantId
                row[assetId] = request.assetId
                row[category] = request.category
                row[action] = request.action
                row[payloadHashColumn] = payloadHash
                row[previousHashColumn] = previousHash
                row[chainHash] = entryHash
                row[metadata] = metadataJson
                row[createdAt] = now
                row[position] = previousIndex + 1
            }
            AuditAnchors.update({ AuditAnchors.id eq anchorRow[AuditAnchors.id] }) {
                it[lastHash] = entryHash
                it[updatedAt] = now
            }
            AuditEventResponse(
                eventId = eventId.toString(),
                tenantId = request.tenantId,
                assetId = request.assetId,
                category = request.category,
                action = request.action,
                payloadHash = payloadHash,
                previousHash = previousHash,
                chainHash = entryHash,
                metadata = request.metadata,
                createdAt = now.toString(),
                position = previousIndex + 1
            )
        }
    }

    fun listEventsByAsset(assetId: String): List<AuditEventResponse> = transaction(database) {
        AuditEvents.select { AuditEvents.assetId eq UUID.fromString(assetId) }
            .orderBy(AuditEvents.position to SortOrder.ASC)
            .map {
                AuditEventResponse(
                    eventId = it[AuditEvents.id].value.toString(),
                    tenantId = it[AuditEvents.tenantId],
                    assetId = it[AuditEvents.assetId].toString(),
                    category = it[AuditEvents.category],
                    action = it[AuditEvents.action],
                    payloadHash = it[AuditEvents.payloadHashColumn],
                    previousHash = it[AuditEvents.previousHashColumn],
                    chainHash = it[AuditEvents.chainHash],
                    metadata = Json.decodeFromString(MapSerializer, it[AuditEvents.metadata]),
                    createdAt = it[AuditEvents.createdAt].toString(),
                    position = it[AuditEvents.position]
                )
            }
    }

    fun chainHead(): ChainHead = transaction(database) {
        AuditAnchors.selectAll().limit(1).first().let {
            ChainHead(
                lastHash = it[AuditAnchors.lastHash],
                updatedAt = it[AuditAnchors.updatedAt].toString()
            )
        }
    }

    private fun chainHash(previousHash: String, payloadHash: String, timestamp: Instant): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(previousHash.toByteArray(StandardCharsets.UTF_8))
        digest.update(payloadHash.toByteArray(StandardCharsets.UTF_8))
        digest.update(timestamp.toString().toByteArray(StandardCharsets.UTF_8))
        return CryptoToolkit.base64Encode(digest.digest())
    }

    companion object {
        private val MapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}

class BlockchainGatewayClient(private val httpClient: HttpClient, private val config: BlockchainConfig) {
    suspend fun publishAnchor(request: AuditAnchorRequest) {
        if (!config.publishAnchors) return
        val response = httpClient.post("${config.baseUrl}/audit/anchors") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to publish audit anchor: ${response.status} ${response.bodyAsText()}")
        }
    }

    suspend fun health(): String {
        val response = httpClient.get("${config.baseUrl}/health")
        return if (response.status.isSuccess()) response.bodyAsText() else "DOWN: ${response.status}"
    }
}

object AuditEvents : UUIDTable("audit_events") {
    val tenantId = varchar("tenant_id", 64)
    val assetId = uuid("asset_id")
    val category = varchar("category", 64)
    val action = varchar("action", 64)
    val payloadHashColumn = text("payload_hash")
    val previousHashColumn = text("previous_hash")
    val chainHash = text("chain_hash")
    val metadata = text("metadata")
    val createdAt = timestamp("created_at")
    val position = integer("position")
}

object AuditAnchors : UUIDTable("audit_anchors") {
    val lastHash = text("last_hash")
    val updatedAt = timestamp("updated_at")
}

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse,
    val database: String,
    val kafka: String,
    val blockchainGateway: String
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String,
    val kafkaTopic: String,
    val blockchainGatewayUrl: String
)

@Serializable
data class AppendAuditEventRequest(
    val tenantId: String,
    val assetId: String,
    val category: String,
    val action: String,
    val payload: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class AuditEventResponse(
    val eventId: String,
    val tenantId: String,
    val assetId: String,
    val category: String,
    val action: String,
    val payloadHash: String,
    val previousHash: String,
    val chainHash: String,
    val metadata: Map<String, String>,
    val createdAt: String,
    val position: Int
)

@Serializable
data class ChainHead(val lastHash: String, val updatedAt: String)

@Serializable
data class AuditAnchorRequest(val eventId: String, val hash: String, val createdAt: String)

@Serializable
data class AuditServiceConfig(
    val kms: KmsClientConfig,
    val database: DatabaseConfig,
    val kafka: KafkaConfig,
    val blockchain: BlockchainConfig
) {
    companion object {
        fun fromEnvironment(): AuditServiceConfig {
            val kmsBase = System.getenv("KMS_BASE_URL") ?: "http://localhost:8088"
            val kmsToken = System.getenv("KMS_ACCESS_TOKEN")
            val kmsTimeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: 10000L

            val jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/platform"
            val jdbcUser = System.getenv("JDBC_USERNAME") ?: "platform"
            val jdbcPassword = System.getenv("JDBC_PASSWORD") ?: "platform"

            val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "kafka:9092"
            val kafkaTopic = System.getenv("KAFKA_AUDIT_TOPIC") ?: "audit.events"

            val gatewayBase = System.getenv("BLOCKCHAIN_GATEWAY_BASE_URL") ?: "http://blockchain-gateway:8080"
            val publish = (System.getenv("BLOCKCHAIN_PUBLISH_ANCHORS") ?: "true").toBoolean()
            val initialHash = System.getenv("AUDIT_CHAIN_INITIAL_HASH") ?: CryptoToolkit.base64Encode(ByteArray(32))

            return AuditServiceConfig(
                kms = KmsClientConfig(baseUrl = kmsBase, accessToken = kmsToken, requestTimeoutMillis = kmsTimeout),
                database = DatabaseConfig(jdbcUrl, jdbcUser, jdbcPassword),
                kafka = KafkaConfig(kafkaBootstrap, kafkaTopic),
                blockchain = BlockchainConfig(gatewayBase, publish, initialHash)
            )
        }
    }
}

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
data class KafkaConfig(val bootstrapServers: String, val topic: String) {
    fun toProperties(): Properties = Properties().apply {
        put("bootstrap.servers", bootstrapServers)
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("acks", "all")
    }
}

@Serializable
data class BlockchainConfig(val baseUrl: String, val publishAnchors: Boolean, val initialHash: String)

private const val CAPABILITIES = """
Audit service seals events in PostgreSQL with an immutable hash chain, emits Kafka notifications, and anchors the chain head through the blockchain gateway while preserving delegated KMS operations.
"""
