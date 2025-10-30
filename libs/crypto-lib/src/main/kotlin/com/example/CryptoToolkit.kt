package com.example

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Cryptographic helper routines shared across services.
 */
object CryptoToolkit {
    private val secureRandom = SecureRandom()

    private const val AES_GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256
    private const val AES_KEY_BYTES = AES_KEY_BITS / 8

    fun generateNonce(length: Int = 12): ByteArray = ByteArray(length).also(secureRandom::nextBytes)

    fun generateAesKey(): SecretKey = SecretKeySpec(ByteArray(AES_KEY_BYTES).also(secureRandom::nextBytes), "AES")

    fun aesGcmEncrypt(plaintext: ByteArray, aad: ByteArray, key: SecretKey, nonce: ByteArray = generateNonce()): EnvelopePayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        val tag = ciphertext.copyOfRange(ciphertext.size - TAG_LENGTH, ciphertext.size)
        val encrypted = ciphertext.copyOfRange(0, ciphertext.size - TAG_LENGTH)
        return EnvelopePayload(nonce = nonce, ciphertext = encrypted, tag = tag, aad = aad)
    }

    fun aesGcmDecrypt(payload: EnvelopePayload, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_BITS, payload.nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(payload.aad)
        val combined = payload.ciphertext + payload.tag
        return cipher.doFinal(combined)
    }

    fun wrapAesKey(keyToWrap: SecretKey, wrappingKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AESWrap")
        cipher.init(Cipher.WRAP_MODE, wrappingKey)
        return cipher.wrap(keyToWrap)
    }

    fun unwrapAesKey(wrapped: ByteArray, wrappingKey: SecretKey): SecretKey {
        val cipher = Cipher.getInstance("AESWrap")
        cipher.init(Cipher.UNWRAP_MODE, wrappingKey)
        return cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
    }

    fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int = AES_KEY_BYTES): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, inputKeyMaterial)
        var previous = ByteArray(0)
        val result = ByteArray(length)
        var offset = 0
        var blockIndex = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(blockIndex.toByte())
            previous = mac.doFinal()
            val copyLength = min(previous.size, length - offset)
            System.arraycopy(previous, 0, result, offset, copyLength)
            offset += copyLength
            blockIndex++
        }
        zeroize(prk)
        zeroize(previous)
        return result
    }

    fun deriveSessionKey(tmk: ByteArray, nonce: ByteArray, context: String): SecretKey {
        val info = context.toByteArray(StandardCharsets.UTF_8)
        val derived = hkdf(tmk, nonce, info)
        return SecretKeySpec(derived, "AES")
    }

    fun ecdhKeyAgreement(peerPublicKey: ByteArray, privateKey: PrivateKey): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val publicSpec = X509EncodedKeySpec(peerPublicKey)
        val publicKey = keyFactory.generatePublic(publicSpec)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    fun generateEphemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return generator.generateKeyPair()
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int = 120_000, keyLength: Int = AES_KEY_BITS): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, keyLength)
        val secret = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return secret
    }

    fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }

    fun zeroize(chars: CharArray) {
        chars.fill('\u0000')
    }

    fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    fun base64Decode(data: String): ByteArray = Base64.getDecoder().decode(data)

    private const val TAG_LENGTH = 16
}

/**
 * Simple container for envelope encryption results.
 */
data class EnvelopePayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
    val aad: ByteArray
) {
    fun encode(): EncodedEnvelopePayload = EncodedEnvelopePayload(
        nonce = CryptoToolkit.base64Encode(nonce),
        ciphertext = CryptoToolkit.base64Encode(ciphertext),
        tag = CryptoToolkit.base64Encode(tag),
        aad = CryptoToolkit.base64Encode(aad)
    )
}

data class EncodedEnvelopePayload(
    val nonce: String,
    val ciphertext: String,
    val tag: String,
    val aad: String
) {
    fun decode(): EnvelopePayload = EnvelopePayload(
        nonce = CryptoToolkit.base64Decode(nonce),
        ciphertext = CryptoToolkit.base64Decode(ciphertext),
        tag = CryptoToolkit.base64Decode(tag),
        aad = CryptoToolkit.base64Decode(aad)
    )
}
