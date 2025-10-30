package com.example

import com.example.kmsclient.KmsClient
import com.example.kmsclient.KmsClientConfig
import com.example.kmsclient.KmsHealthResponse
import com.example.kmsclient.UnwrapDekRequest
import com.example.kmsclient.WrapDekRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.javatime.timestamp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.minio.MinioClient
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.PutObjectArgs
import org.jetbrains.exposed.sql.ResultRow

fun main() {
    io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging)

    val config = DataVaultServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(config.kms)
    val dataSource = HikariDataSource(config.database.toHikari())
    val database = Database.connect(dataSource)
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(VaultAssets)
    }

    val kafkaProducer = KafkaProducer<String, String>(config.kafka.toProperties())
    val minioClient = config.storage.createClient()
    ensureBucket(minioClient, config.storage.bucket)

    environment.monitor.subscribe(ApplicationStopPreparing) {
        kafkaProducer.close()
        kmsClient.close()
        dataSource.close()
    }

    val repository = DataVaultRepository(database, config)

    routing {
        get("/health") {
            val dbStatus = runCatching {
                transaction(database) { VaultAssets.selectAll().limit(1).count() }
            }.fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })

            val kafkaStatus = runCatching {
                kafkaProducer.partitionsFor(config.kafka.assetsTopic)
            }.fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })

            val minioStatus = runCatching {
                minioClient.listBuckets()
            }.fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })

            call.respond(
                HttpStatusCode.OK,
                HealthResponse(
                    service = "data-vault-service",
                    status = "UP",
                    kms = kmsClient.health(),
                    database = dbStatus,
                    kafka = kafkaStatus,
                    objectStorage = minioStatus
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "data-vault-service",
                    description = CAPABILITIES,
                    kmsBaseUrl = config.kms.baseUrl,
                    kafkaTopic = config.kafka.assetsTopic,
                    storageBucket = config.storage.bucket
                )
            )
        }

        route("/vault") {
            post("/assets") {
                val request = call.receive<CreateAssetRequest>()
                val result = repository.createAsset(request, kmsClient, kafkaProducer, minioClient)
                call.respond(HttpStatusCode.Created, result)
            }

            get("/assets/{id}") {
                val assetId = UUID.fromString(call.parameters.getOrFail("id"))
                val includeCiphertext = call.request.queryParameters["includeCiphertext"].toBoolean()
                val dto = repository.fetchDecryptedAsset(assetId, kmsClient, includeCiphertext)
                call.respond(dto)
            }

            get("/assets/{id}/envelope") {
                val assetId = UUID.fromString(call.parameters.getOrFail("id"))
                val envelope = repository.fetchEnvelope(assetId)
                call.respond(envelope ?: HttpStatusCode.NotFound)
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

private fun ensureBucket(client: MinioClient, bucket: String) {
    val exists = runCatching { client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()) }.getOrNull() ?: false
    if (!exists) {
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
    }
}

class DataVaultRepository(
    private val database: Database,
    private val config: DataVaultServiceConfig
) {
    suspend fun createAsset(
        request: CreateAssetRequest,
        kmsClient: KmsClient,
        producer: KafkaProducer<String, String>,
        minioClient: MinioClient
    ): CreateAssetResponse = withContext(Dispatchers.IO) {
        val dek = CryptoToolkit.generateAesKey()
        val aad = buildAad(request)
        val envelope = CryptoToolkit.aesGcmEncrypt(
            plaintext = request.plaintext.toByteArray(StandardCharsets.UTF_8),
            aad = aad,
            key = dek
        )
        val dekBase64 = CryptoToolkit.base64Encode(dek.encoded)
        val wrapped = kmsClient.wrapDek(
            WrapDekRequest(
                tenantId = request.tenantId,
                dekPlain = dekBase64,
                tmkVersion = request.tmkVersion
            )
        )

        val blindIndex = computeBlindIndex(request.tenantId, request.metadataHash)
        val assetId = UUID.randomUUID()
        val createdAt = Instant.now()
        val attachments = request.attachments.orEmpty()

        attachments.forEach { attachment ->
            val contentBytes = CryptoToolkit.base64Decode(attachment.base64Data)
            val objectName = "${assetId}/${attachment.filename}"
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(config.storage.bucket)
                    .`object`(objectName)
                    .stream(contentBytes.inputStream(), contentBytes.size.toLong(), -1)
                    .contentType(attachment.contentType ?: "application/octet-stream")
                    .build()
            )
        }

        transaction(database) {
            VaultAssets.insert { row ->
                row[VaultAssets.id] = assetId
                row[VaultAssets.tenantId] = request.tenantId
                row[VaultAssets.type] = request.type
                row[VaultAssets.metadataHash] = request.metadataHash
                row[VaultAssets.state] = request.state
                row[VaultAssets.ownerOrg] = request.ownerOrg
                row[VaultAssets.nonce] = CryptoToolkit.base64Encode(envelope.nonce)
                row[VaultAssets.aad] = CryptoToolkit.base64Encode(envelope.aad)
                row[VaultAssets.ciphertext] = CryptoToolkit.base64Encode(envelope.ciphertext)
                row[VaultAssets.tag] = CryptoToolkit.base64Encode(envelope.tag)
                row[VaultAssets.wrappedDek] = wrapped.wrappedDek
                row[VaultAssets.dekAlgo] = wrapped.dekAlgo
                row[VaultAssets.tmkId] = wrapped.tmkId
                row[VaultAssets.tmkVersion] = wrapped.tmkVersion
                row[VaultAssets.blindIndex] = blindIndex
                row[VaultAssets.createdAt] = createdAt
                row[VaultAssets.updatedAt] = createdAt
            }
        }

        val payload = Json.encodeToString(AssetCreatedEvent(assetId.toString(), request.tenantId, request.type, request.metadataHash))
        producer.send(ProducerRecord(config.kafka.assetsTopic, assetId.toString(), payload))

        CreateAssetResponse(
            id = assetId.toString(),
            tenantId = request.tenantId,
            metadataHash = request.metadataHash,
            wrappedDek = wrapped.wrappedDek,
            tmkId = wrapped.tmkId,
            tmkVersion = wrapped.tmkVersion,
            createdAt = createdAt.toString(),
            storageBucket = config.storage.bucket,
            attachments = attachments.map { StoredAttachment(it.filename) }
        )
    }

    suspend fun fetchDecryptedAsset(assetId: UUID, kmsClient: KmsClient, includeCiphertext: Boolean): DecryptedAssetResponse = withContext(Dispatchers.IO) {
        val record = transaction(database) {
            VaultAssets.select { VaultAssets.id eq assetId }.singleOrNull()
        } ?: throw NotFoundException("Asset $assetId not found")

        val dek = retrieveDek(record, kmsClient)
        val plaintext = decrypt(record, dek)

        DecryptedAssetResponse(
            id = assetId.toString(),
            tenantId = record[VaultAssets.tenantId],
            type = record[VaultAssets.type],
            state = record[VaultAssets.state],
            ownerOrg = record[VaultAssets.ownerOrg],
            metadataHash = record[VaultAssets.metadataHash],
            plaintext = plaintext,
            aad = record[VaultAssets.aad],
            ciphertext = record[VaultAssets.ciphertext].takeIf { includeCiphertext },
            tag = record[VaultAssets.tag].takeIf { includeCiphertext }
        )
    }

    fun fetchEnvelope(assetId: UUID): EnvelopeResponse? = transaction(database) {
        VaultAssets.select { VaultAssets.id eq assetId }.singleOrNull()?.let {
            EnvelopeResponse(
                cipherAlgo = "AES-256-GCM",
                nonce = it[VaultAssets.nonce],
                aad = it[VaultAssets.aad],
                ciphertext = it[VaultAssets.ciphertext],
                tag = it[VaultAssets.tag],
                wrappedDek = it[VaultAssets.wrappedDek],
                dekAlgo = it[VaultAssets.dekAlgo],
                tmkId = it[VaultAssets.tmkId],
                tmkVersion = it[VaultAssets.tmkVersion],
                tenantId = it[VaultAssets.tenantId],
                assetId = it[VaultAssets.id].toString()
            )
        }
    }

    private suspend fun retrieveDek(row: ResultRow, kmsClient: KmsClient): SecretKey {
        val unwrap = kmsClient.unwrapDek(
            UnwrapDekRequest(
                tenantId = row[VaultAssets.tenantId],
                wrappedDek = row[VaultAssets.wrappedDek],
                tmkVersion = row[VaultAssets.tmkVersion]
            )
        )
        val decoded = CryptoToolkit.base64Decode(unwrap.dekPlain)
        return SecretKeySpec(decoded, 0, decoded.size, "AES")
    }

    private fun decrypt(row: ResultRow, dek: SecretKey): String {
        val payload = EnvelopePayload(
            nonce = CryptoToolkit.base64Decode(row[VaultAssets.nonce]),
            ciphertext = CryptoToolkit.base64Decode(row[VaultAssets.ciphertext]),
            tag = CryptoToolkit.base64Decode(row[VaultAssets.tag]),
            aad = CryptoToolkit.base64Decode(row[VaultAssets.aad])
        )
        val plaintext = CryptoToolkit.aesGcmDecrypt(payload, dek)
        return plaintext.toString(StandardCharsets.UTF_8)
    }

    private fun computeBlindIndex(tenantId: String, metadataHash: String): String {
        val macKey = config.blindIndexPepper
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(tenantId.toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        mac.update(metadataHash.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(mac.doFinal())
    }

    private fun buildAad(request: CreateAssetRequest): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(request.tenantId.toByteArray(StandardCharsets.UTF_8))
        digest.update(request.type.toByteArray(StandardCharsets.UTF_8))
        digest.update(request.schemaVersion.toByteArray(StandardCharsets.UTF_8))
        return digest.digest()
    }
}

object VaultAssets : Table("vault_assets") {
    val id = uuid("id")
    val tenantId = varchar("tenant_id", 64)
    val type = varchar("type", 64)
    val metadataHash = varchar("metadata_hash", 128)
    val state = varchar("state", 64)
    val ownerOrg = varchar("owner_org", 128)
    val nonce = text("nonce")
    val aad = text("aad")
    val ciphertext = text("ciphertext")
    val tag = text("tag")
    val wrappedDek = text("wrapped_dek")
    val dekAlgo = varchar("dek_algo", 32)
    val tmkId = varchar("tmk_id", 64)
    val tmkVersion = integer("tmk_version")
    val blindIndex = varchar("blind_index", 128)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class NotFoundException(message: String) : RuntimeException(message)

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse,
    val database: String,
    val kafka: String,
    val objectStorage: String
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String,
    val kafkaTopic: String,
    val storageBucket: String
)

@Serializable
data class CreateAssetRequest(
    val tenantId: String,
    val type: String,
    val metadataHash: String,
    val state: String,
    val ownerOrg: String,
    val plaintext: String,
    val schemaVersion: String = "v1",
    val tmkVersion: Int? = null,
    val attachments: List<AttachmentUpload>? = null
)

@Serializable
data class AttachmentUpload(
    val filename: String,
    val contentType: String? = null,
    val base64Data: String
)

@Serializable
data class StoredAttachment(val filename: String)

@Serializable
data class CreateAssetResponse(
    val id: String,
    val tenantId: String,
    val metadataHash: String,
    val wrappedDek: String,
    val tmkId: String,
    val tmkVersion: Int,
    val createdAt: String,
    val storageBucket: String,
    val attachments: List<StoredAttachment>
)

@Serializable
data class DecryptedAssetResponse(
    val id: String,
    val tenantId: String,
    val type: String,
    val state: String,
    val ownerOrg: String,
    val metadataHash: String,
    val plaintext: String,
    val aad: String,
    val ciphertext: String?,
    val tag: String?
)

@Serializable
data class EnvelopeResponse(
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

@Serializable
data class AssetCreatedEvent(
    val assetId: String,
    val tenantId: String,
    val type: String,
    val metadataHash: String
)

data class DataVaultServiceConfig(
    val kms: KmsClientConfig,
    val database: DatabaseConfig,
    val kafka: KafkaConfig,
    val storage: StorageConfig,
    val blindIndexPepper: ByteArray
) {
    companion object {
        private const val DEFAULT_KMS_URL = "http://localhost:8088"
        private const val DEFAULT_KAFKA_BOOTSTRAP = "kafka:9092"
        private const val DEFAULT_KAFKA_TOPIC = "vault.assets.created"
        private const val DEFAULT_BUCKET = "vault-assets"

        fun fromEnvironment(): DataVaultServiceConfig {
            val kmsBase = System.getenv("KMS_BASE_URL") ?: DEFAULT_KMS_URL
            val accessToken = System.getenv("KMS_ACCESS_TOKEN")
            val timeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: 10000L

            val jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/platform"
            val jdbcUser = System.getenv("JDBC_USERNAME") ?: "platform"
            val jdbcPassword = System.getenv("JDBC_PASSWORD") ?: "platform"

            val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: DEFAULT_KAFKA_BOOTSTRAP
            val kafkaTopic = System.getenv("KAFKA_ASSET_TOPIC") ?: DEFAULT_KAFKA_TOPIC

            val storageEndpoint = System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
            val storageAccess = System.getenv("MINIO_ACCESS_KEY") ?: "minioadmin"
            val storageSecret = System.getenv("MINIO_SECRET_KEY") ?: "minioadmin"
            val bucket = System.getenv("MINIO_VAULT_BUCKET") ?: DEFAULT_BUCKET

            val pepper = System.getenv("BLIND_INDEX_PEPPER_BASE64")?.let { CryptoToolkit.base64Decode(it) }
                ?: CryptoToolkit.generateNonce(32)

            return DataVaultServiceConfig(
                kms = KmsClientConfig(baseUrl = kmsBase, accessToken = accessToken, requestTimeoutMillis = timeout),
                database = DatabaseConfig(jdbcUrl, jdbcUser, jdbcPassword),
                kafka = KafkaConfig(kafkaBootstrap, kafkaTopic),
                storage = StorageConfig(storageEndpoint, storageAccess, storageSecret, bucket),
                blindIndexPepper = pepper
            )
        }
    }
}

data class DatabaseConfig(val jdbcUrl: String, val username: String, val password: String) {
    fun toHikari(): HikariConfig = HikariConfig().apply {
        jdbcUrl = this@DatabaseConfig.jdbcUrl
        username = this@DatabaseConfig.username
        password = this@DatabaseConfig.password
        maximumPoolSize = 5
        driverClassName = "org.postgresql.Driver"
    }
}

data class KafkaConfig(val bootstrapServers: String, val assetsTopic: String) {
    fun toProperties(): Properties = Properties().apply {
        put("bootstrap.servers", bootstrapServers)
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("acks", "all")
    }
}

data class StorageConfig(
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String
) {
    fun createClient(): MinioClient = MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .build()
}

private fun String?.toBoolean(): Boolean = this != null && listOf("1", "true", "yes", "on").contains(this.lowercase())

private const val CAPABILITIES = """
Data vault service persists envelopes in PostgreSQL with blind indexes, stores attachments in MinIO and emits Kafka events after wrapping DEKs with the central KMS.
"""
