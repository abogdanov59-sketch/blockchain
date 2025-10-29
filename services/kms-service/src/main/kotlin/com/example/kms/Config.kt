package com.example.kms

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Application configuration derived from environment variables.
 */
data class KmsConfig(
    val host: String = System.getenv("KMS_HOST") ?: "0.0.0.0",
    val port: Int = (System.getenv("KMS_PORT") ?: "8080").toInt(),
    val database: DatabaseConfig = DatabaseConfig.fromEnv(),
    val infisical: InfisicalConfig = InfisicalConfig.fromEnv(),
    val pkcs11: Pkcs11Config = Pkcs11Config.fromEnv(),
    val security: SecurityConfig = SecurityConfig.fromEnv()
)

/**
 * JDBC configuration for Exposed/HikariCP.
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClass: String = System.getenv("JDBC_DRIVER") ?: "org.postgresql.Driver",
    val maximumPoolSize: Int = (System.getenv("JDBC_POOL_SIZE") ?: "10").toInt(),
    val connectionTimeoutMs: Long = (System.getenv("JDBC_CONNECTION_TIMEOUT_MS") ?: "30000").toLong()
) {
    companion object {
        fun fromEnv(): DatabaseConfig = DatabaseConfig(
            jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/kms",
            username = System.getenv("JDBC_USERNAME") ?: "kms",
            password = System.getenv("JDBC_PASSWORD") ?: "kms"
        )
    }
}

/**
 * Infisical access configuration. Secrets are stored under [workspaceSlug]/[environmentSlug].
 */
data class InfisicalConfig(
    val baseUrl: String,
    val workspaceSlug: String,
    val environmentSlug: String,
    val serviceToken: String,
    val cacheTtl: Duration
) {
    companion object {
        fun fromEnv(): InfisicalConfig = InfisicalConfig(
            baseUrl = System.getenv("INFISICAL_BASE_URL") ?: "https://infisical.local",
            workspaceSlug = System.getenv("INFISICAL_WORKSPACE") ?: "foundation",
            environmentSlug = System.getenv("INFISICAL_ENVIRONMENT") ?: "dev",
            serviceToken = System.getenv("INFISICAL_SERVICE_TOKEN") ?: "dev-token",
            cacheTtl = (System.getenv("INFISICAL_CACHE_TTL_MINUTES") ?: "5").toInt().minutes
        )
    }
}

/**
 * PKCS#11 configuration. When disabled, in-memory software fallback is used.
 */
data class Pkcs11Config(
    val enabled: Boolean,
    val libraryPath: String?,
    val slotId: Int?,
    val userPin: CharArray?,
    val rootKeyAlias: String?
) {
    companion object {
        fun fromEnv(): Pkcs11Config {
            val enabled = (System.getenv("PKCS11_ENABLED") ?: "false").toBoolean()
            return Pkcs11Config(
                enabled = enabled,
                libraryPath = System.getenv("PKCS11_LIBRARY"),
                slotId = System.getenv("PKCS11_SLOT")?.toInt(),
                userPin = System.getenv("PKCS11_PIN")?.toCharArray(),
                rootKeyAlias = System.getenv("PKCS11_ROOT_KEY_ALIAS") ?: "rk"
            )
        }
    }
}

/**
 * JWT/OIDC configuration. For local development, a static bearer token can be used.
 */
data class SecurityConfig(
    val issuer: String?,
    val audience: String?,
    val jwksUrl: String?,
    val devBypassToken: String?
) {
    companion object {
        fun fromEnv(): SecurityConfig = SecurityConfig(
            issuer = System.getenv("OIDC_ISSUER"),
            audience = System.getenv("OIDC_AUDIENCE"),
            jwksUrl = System.getenv("OIDC_JWKS_URL"),
            devBypassToken = System.getenv("DEV_BYPASS_TOKEN")
        )
    }
}
