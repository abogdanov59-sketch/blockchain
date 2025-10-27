package com.example

import java.security.SecureRandom

/**
 * Minimal crypto toolkit placeholder used by services while detailed implementations are built.
 */
object CryptoToolkit {
    private val secureRandom = SecureRandom()

    fun generateNonce(length: Int = 12): ByteArray = ByteArray(length).also(secureRandom::nextBytes)

    fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }
}
