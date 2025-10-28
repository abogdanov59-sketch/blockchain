package com.example.kms

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.interfaces.RSAKeyProvider
import java.net.URL
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

object JwtVerifierFactory {
    fun create(jwksUrl: String, issuer: String, audience: String?): JWTVerifier {
        val jwkProvider = JwkProviderBuilder(URL(jwksUrl))
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
        val keyProvider = JwkRsaKeyProvider(jwkProvider)
        val verification = JWT.require(Algorithm.RSA256(keyProvider))
            .withIssuer(issuer)
        if (audience != null) {
            verification.withAudience(audience)
        }
        return verification.build()
    }

    private class JwkRsaKeyProvider(private val provider: JwkProvider) : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey {
            require(!keyId.isNullOrBlank()) { "JWT missing key id" }
            val jwk = provider.get(keyId)
            return jwk.publicKey as RSAPublicKey
        }

        override fun getPrivateKey(): RSAPrivateKey? = null

        override fun getPrivateKeyId(): String? = null
    }
}
