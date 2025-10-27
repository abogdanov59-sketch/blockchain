package com.example.kms

import com.example.CryptoToolkit
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import sun.security.pkcs11.SunPKCS11

/**
 * Abstraction over PKCS#11 operations. Wraps/unwraps tenant master keys under the root key.
 */
interface Pkcs11Module : AutoCloseable {
    fun wrapUnderRootKey(secretKey: SecretKey): ByteArray
    fun unwrapUnderRootKey(wrapped: ByteArray): SecretKey
}

/**
 * PKCS#11-backed implementation using SoftHSM2 (or a compatible HSM) for development and production parity.
 */
class SoftHsmPkcs11Module(config: Pkcs11Config) : Pkcs11Module {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val provider: Provider
    private val keyStore: KeyStore
    private val rootKeyAlias: String
    private val pin: CharArray

    init {
        require(!config.libraryPath.isNullOrBlank()) { "PKCS11 library path must be provided" }
        require(config.userPin != null) { "PKCS11 user PIN must be provided" }
        require(config.slotId != null) { "PKCS11 slot id must be provided" }
        pin = config.userPin.copyOf()
        val pkcs11Config = """
            name = SoftHSM
            library = ${config.libraryPath}
            slot = ${config.slotId}
        """.trimIndent()
        provider = SunPKCS11(ByteArrayInputStream(pkcs11Config.toByteArray()))
        Security.addProvider(provider)
        keyStore = KeyStore.getInstance("PKCS11", provider).apply { load(null, pin) }
        rootKeyAlias = config.rootKeyAlias ?: error("Root key alias must be set")
        if (!keyStore.containsAlias(rootKeyAlias)) {
            logger.error("Root key with alias {} is absent in HSM", rootKeyAlias)
            throw IllegalStateException("Root key missing in PKCS#11 token")
        }
    }

    override fun wrapUnderRootKey(secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AESWrap", provider)
        cipher.init(Cipher.WRAP_MODE, getRootKey())
        return cipher.wrap(secretKey)
    }

    override fun unwrapUnderRootKey(wrapped: ByteArray): SecretKey {
        val cipher = Cipher.getInstance("AESWrap", provider)
        cipher.init(Cipher.UNWRAP_MODE, getRootKey())
        return cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
    }

    private fun getRootKey(): SecretKey = keyStore.getKey(rootKeyAlias, pin) as SecretKey

    override fun close() {
        CryptoToolkit.zeroize(pin)
        Security.removeProvider(provider.name)
    }
}

/**
 * Software fallback used in local development when PKCS#11 is not available.
 */
class InMemoryPkcs11Module : Pkcs11Module {
    private val rootKey: SecretKey = run {
        val seed = System.getenv("DEV_RK_SEED")?.toCharArray()
        val keyBytes = if (seed != null) {
            try {
                CryptoToolkit.pbkdf2(seed, byteArrayOf(0, 1, 2, 3))
            } finally {
                CryptoToolkit.zeroize(seed)
            }
        } else {
            CryptoToolkit.generateAesKey().encoded
        }
        SecretKeySpec(keyBytes, "AES")
    }

    override fun wrapUnderRootKey(secretKey: SecretKey): ByteArray = CryptoToolkit.wrapAesKey(secretKey, rootKey)

    override fun unwrapUnderRootKey(wrapped: ByteArray): SecretKey = CryptoToolkit.unwrapAesKey(wrapped, rootKey)

    override fun close() {
        CryptoToolkit.zeroize(rootKey.encoded)
    }
}
