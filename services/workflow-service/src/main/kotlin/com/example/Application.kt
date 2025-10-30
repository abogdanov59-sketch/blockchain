package com.example

import com.example.kmsclient.InvalidateTmkRequest
import com.example.kmsclient.KmsClient
import com.example.kmsclient.KmsClientConfig
import com.example.kmsclient.KmsHealthResponse
import com.example.kmsclient.TmkVersionDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.contentType
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.io.InputStream
import java.time.Instant
import java.util.Properties
import java.util.UUID

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ServerContentNegotiation) { json() }
    install(CallLogging)

    val config = WorkflowServiceConfig.fromEnvironment()
    val kmsClient = KmsClient(config.kms)

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(Logging)
        install(Auth) {
            basic {
                sendWithoutRequest { true }
                credentials { io.ktor.client.plugins.auth.providers.BasicAuthCredentials(config.flowable.username, config.flowable.password) }
            }
        }
    }
    val flowableClient = FlowableClient(httpClient, config.flowable)
    val kafkaProducer = KafkaProducer<String, String>(config.kafka.toProperties())

    environment.monitor.subscribe(ApplicationStopPreparing) {
        kafkaProducer.close()
        httpClient.close()
        kmsClient.close()
    }

    if (config.deployment.autoDeploy) {
        FlowableDeployer(flowableClient).deployFromClasspath(config.deployment)
    }

    routing {
        get("/health") {
            val flowableStatus = runCatching { flowableClient.health() }
                .fold(onSuccess = { it }, onFailure = { "DOWN: ${it.message}" })
            val kafkaStatus = runCatching { kafkaProducer.partitionsFor(config.kafka.topic) }
                .fold(onSuccess = { "UP" }, onFailure = { "DOWN: ${it.message}" })
            call.respond(
                HealthResponse(
                    service = "workflow-service",
                    status = "UP",
                    kms = kmsClient.health(),
                    flowable = flowableStatus,
                    kafka = kafkaStatus
                )
            )
        }

        get("/capabilities") {
            call.respond(
                ServiceCapabilities(
                    service = "workflow-service",
                    description = CAPABILITIES,
                    kmsBaseUrl = config.kms.baseUrl,
                    flowableBaseUrl = config.flowable.baseUrl,
                    kafkaTopic = config.kafka.topic
                )
            )
        }

        route("/workflow") {
            post("/processes/{key}/start") {
                val key = call.parameters.getOrFail("key")
                val request = call.receive<StartProcessRequest>()
                val instance = flowableClient.startProcess(key, request)
                val event = WorkflowEvent(
                    type = "PROCESS_STARTED",
                    tenantId = request.tenantId,
                    reference = instance.id,
                    processDefinitionKey = key,
                    variables = request.variables
                )
                kafkaProducer.send(ProducerRecord(config.kafka.topic, event.reference, Json.encodeToString(event)))
                call.respond(HttpStatusCode.Accepted, instance)
            }

            post("/tasks/{id}/complete") {
                val taskId = call.parameters.getOrFail("id")
                val request = call.receive<CompleteTaskRequest>()
                flowableClient.completeTask(taskId, request)
                val event = WorkflowEvent(
                    type = "TASK_COMPLETED",
                    tenantId = request.tenantId,
                    reference = taskId,
                    processDefinitionKey = request.processDefinitionKey,
                    variables = request.variables
                )
                kafkaProducer.send(ProducerRecord(config.kafka.topic, UUID.randomUUID().toString(), Json.encodeToString(event)))
                call.respond(HttpStatusCode.Accepted)
            }

            post("/signals/{signal}") {
                val signal = call.parameters.getOrFail("signal")
                val request = call.receive<SignalRequest>()
                flowableClient.sendSignal(signal, request)
                val event = WorkflowEvent(
                    type = "SIGNAL_SENT",
                    tenantId = request.tenantId,
                    reference = signal,
                    processDefinitionKey = request.processDefinitionKey,
                    variables = request.variables
                )
                kafkaProducer.send(ProducerRecord(config.kafka.topic, UUID.randomUUID().toString(), Json.encodeToString(event)))
                call.respond(HttpStatusCode.Accepted)
            }
        }

        route("/kms") {
            get("/health") { call.respond(kmsClient.health()) }
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

class FlowableDeployer(private val flowableClient: FlowableClient) {
    suspend fun deployFromClasspath(config: FlowableDeploymentConfig) = withContext(Dispatchers.IO) {
        val resources = mutableListOf<DeploymentResource>()
        config.processResources.forEach { path ->
            loadResource(path)?.let { resources += DeploymentResource(path.substringAfterLast('/'), ContentType.Application.Xml, it) }
        }
        config.decisionResources.forEach { path ->
            loadResource(path)?.let { resources += DeploymentResource(path.substringAfterLast('/'), ContentType.Application.Xml, it) }
        }
        if (resources.isNotEmpty()) {
            flowableClient.deploy(config.deploymentName ?: "auto-deployment", resources)
        }
    }

    private fun loadResource(path: String): ByteArray? {
        val stream: InputStream = Thread.currentThread().contextClassLoader.getResourceAsStream(path) ?: return null
        return stream.use { it.readAllBytes() }
    }
}

class FlowableClient(private val httpClient: HttpClient, private val config: FlowableConfig) {
    suspend fun health(): String {
        val response = httpClient.get("${config.baseUrl}/actuator/health")
        return if (response.status.isSuccess()) response.bodyAsText() else "DOWN: ${response.status}"
    }

    suspend fun deploy(name: String, resources: List<DeploymentResource>) {
        val form = formData {
            resources.forEach { resource ->
                append(
                    resource.filename,
                    resource.bytes,
                    Headers.build {
                        append("Content-Disposition", "form-data; name=\"${resource.filename}\"; filename=\"${resource.filename}\"")
                        append("Content-Type", resource.contentType.toString())
                    }
                )
            }
        }
        val response = httpClient.post("${config.baseUrl}/repository/deployments") {
            setBody(MultiPartFormDataContent(form))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to deploy Flowable models: ${response.status} ${response.bodyAsText()}")
        }
    }

    suspend fun startProcess(key: String, request: StartProcessRequest): FlowableProcessInstance {
        val payload = FlowableStartRequest(
            processDefinitionKey = key,
            tenantId = request.tenantId,
            businessKey = request.businessKey,
            variables = request.variables.toFlowableVariables()
        )
        val response = httpClient.post("${config.baseUrl}/runtime/process-instances") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to start process $key: ${response.status} ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun completeTask(taskId: String, request: CompleteTaskRequest) {
        val payload = FlowableTaskAction(
            action = "complete",
            variables = request.variables.toFlowableVariables()
        )
        val response = httpClient.post("${config.baseUrl}/runtime/tasks/$taskId") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to complete task $taskId: ${response.status} ${response.bodyAsText()}")
        }
    }

    suspend fun sendSignal(signalName: String, request: SignalRequest) {
        val payload = FlowableSignalRequest(
            signalName = signalName,
            tenantId = request.tenantId,
            variables = request.variables.toFlowableVariables()
        )
        val response = httpClient.post("${config.baseUrl}/runtime/signals") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to signal $signalName: ${response.status} ${response.bodyAsText()}")
        }
    }
}

private fun Map<String, String>.toFlowableVariables(): List<FlowableVariable> = entries.map {
    FlowableVariable(name = it.key, value = it.value)
}

@Serializable
data class FlowableVariable(val name: String, val value: String, val type: String = "string")

@Serializable
data class FlowableStartRequest(
    val processDefinitionKey: String,
    val tenantId: String? = null,
    val businessKey: String? = null,
    val variables: List<FlowableVariable>
)

@Serializable
data class FlowableProcessInstance(
    val id: String,
    val processDefinitionId: String,
    @SerialName("businessKey") val businessKey: String? = null,
    val tenantId: String? = null,
    val ended: Boolean? = null
)

@Serializable
data class FlowableTaskAction(
    val action: String,
    val variables: List<FlowableVariable>
)

@Serializable
data class FlowableSignalRequest(
    val signalName: String,
    val tenantId: String?,
    val variables: List<FlowableVariable>
)

@Serializable
data class StartProcessRequest(
    val tenantId: String,
    val businessKey: String? = null,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class CompleteTaskRequest(
    val tenantId: String,
    val processDefinitionKey: String,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class SignalRequest(
    val tenantId: String,
    val processDefinitionKey: String,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class WorkflowEvent(
    val type: String,
    val tenantId: String,
    val reference: String,
    val processDefinitionKey: String,
    val variables: Map<String, String>,
    val emittedAt: String = Instant.now().toString()
)

@Serializable
data class DeploymentResource(val filename: String, val contentType: ContentType, val bytes: ByteArray)

@Serializable
data class HealthResponse(
    val service: String,
    val status: String,
    val kms: KmsHealthResponse,
    val flowable: String,
    val kafka: String
)

@Serializable
data class ServiceCapabilities(
    val service: String,
    val description: String,
    val kmsBaseUrl: String,
    val flowableBaseUrl: String,
    val kafkaTopic: String
)

@Serializable
data class WorkflowServiceConfig(
    val kms: KmsClientConfig,
    val flowable: FlowableConfig,
    val kafka: KafkaConfig,
    val deployment: FlowableDeploymentConfig
) {
    companion object {
        fun fromEnvironment(): WorkflowServiceConfig {
            val kmsBase = System.getenv("KMS_BASE_URL") ?: "http://localhost:8088"
            val kmsToken = System.getenv("KMS_ACCESS_TOKEN")
            val kmsTimeout = System.getenv("KMS_TIMEOUT_MS")?.toLongOrNull() ?: 10000L

            val flowableBase = System.getenv("FLOWABLE_BASE_URL") ?: "http://flowable:8080/flowable-rest"
            val flowableUser = System.getenv("FLOWABLE_USERNAME") ?: "admin"
            val flowablePassword = System.getenv("FLOWABLE_PASSWORD") ?: "test"

            val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "kafka:9092"
            val kafkaTopic = System.getenv("KAFKA_WORKFLOW_TOPIC") ?: "workflow.events"

            val autoDeploy = (System.getenv("FLOWABLE_AUTO_DEPLOY") ?: "true").toBoolean()
            val deploymentName = System.getenv("FLOWABLE_DEPLOYMENT_NAME")
            val processResources = (System.getenv("FLOWABLE_PROCESS_RESOURCES") ?: DEFAULT_PROCESS_RESOURCES).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val decisionResources = (System.getenv("FLOWABLE_DECISION_RESOURCES") ?: DEFAULT_DECISION_RESOURCES).split(',').map { it.trim() }.filter { it.isNotEmpty() }

            return WorkflowServiceConfig(
                kms = KmsClientConfig(baseUrl = kmsBase, accessToken = kmsToken, requestTimeoutMillis = kmsTimeout),
                flowable = FlowableConfig(flowableBase, flowableUser, flowablePassword),
                kafka = KafkaConfig(kafkaBootstrap, kafkaTopic),
                deployment = FlowableDeploymentConfig(autoDeploy, deploymentName, processResources, decisionResources)
            )
        }

        private const val DEFAULT_PROCESS_RESOURCES = "bpmn/CreateOrderProcess.bpmn,bpmn/ShipmentProcess.bpmn,bpmn/LabProcess.bpmn,bpmn/AcceptanceProcess.bpmn"
        private const val DEFAULT_DECISION_RESOURCES = "dmn/ApprovalRules.dmn"
    }
}

@Serializable
data class FlowableConfig(val baseUrl: String, val username: String, val password: String)

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
data class FlowableDeploymentConfig(
    val autoDeploy: Boolean,
    val deploymentName: String?,
    val processResources: List<String>,
    val decisionResources: List<String>
)

private const val CAPABILITIES = """
Workflow service brokers BPMN/DMN deployments to Flowable, orchestrates process execution via REST, emits lifecycle events to Kafka, and remains integrated with the KMS for tenant master-key governance.
"""
