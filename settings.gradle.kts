pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "modular-platform"

include(
    "libs:crypto-lib",
    "libs:common-models",
    "services:identity-service",
    "services:kms-service",
    "services:data-vault-service",
    "services:sandbox-service",
    "services:workflow-service",
    "services:blockchain-gateway",
    "services:audit-service",
    "services:api-gateway",
    "clients:terminal-agent",
    "chaincode:asset-lifecycle"
)
