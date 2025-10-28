package com.example

import kotlinx.serialization.Serializable

@Serializable
data class Asset(
    val id: String,
    val tenantId: String,
    val type: String,
    val metadataHash: String,
    val state: String,
    val ownerOrg: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class Order(
    val id: String,
    val assetId: String,
    val buyerOrg: String,
    val sellerOrg: String,
    val termsHash: String,
    val state: String,
    val signatures: List<String>,
    val onChainRef: String?
)

@Serializable
data class Shipment(
    val id: String,
    val assetId: String,
    val carrierOrg: String,
    val route: String,
    val events: List<String>,
    val state: String,
    val onChainRef: String?
)

@Serializable
data class LabReport(
    val id: String,
    val assetId: String,
    val labOrg: String,
    val reportHash: String,
    val reportUri: String?,
    val signature: String,
    val result: String,
    val onChainRef: String?
)

@Serializable
data class Grant(
    val id: String,
    val assetId: String,
    val subject: String,
    val scope: String,
    val conditions: String?,
    val validFrom: String,
    val validUntil: String?,
    val status: String
)

@Serializable
data class Envelope(
    val cipherAlgo: String,
    val nonce: String,
    val aad: String,
    val ciphertext: String,
    val tag: String,
    val wrappedDek: String,
    val dekAlgo: String,
    val tmkId: String,
    val version: Int
)

