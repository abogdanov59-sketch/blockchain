package com.example.kms

import com.example.kmsclient.TmkStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

data class TmkVersion(
    val tenantId: String,
    val tmkId: String,
    val version: Int,
    val status: TmkStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)

object TmkVersionsTable : Table("tmk_versions") {
    val tenantId = varchar("tenant_id", 128)
    val tmkId = varchar("tmk_id", 128)
    val version = integer("version")
    val status = enumerationByName("status", 32, TmkStatus::class)
    val createdAt = datetime("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = datetime("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(tenantId, version)
}

class TmkRepository {
    fun createVersion(tenantId: String, tmkId: String, status: TmkStatus): TmkVersion = transaction {
        val version = nextVersion(tenantId)
        TmkVersionsTable.insert {
            it[this.tenantId] = tenantId
            it[this.tmkId] = tmkId
            it[this.version] = version
            it[this.status] = status
        }
        findVersion(tenantId, version) ?: error("Failed to persist TMK version")
    }

    fun markStatus(tenantId: String, version: Int, status: TmkStatus) = transaction {
        val updatedRows = TmkVersionsTable.update({
            (TmkVersionsTable.tenantId eq tenantId) and (TmkVersionsTable.version eq version)
        }) {
            it[TmkVersionsTable.status] = status
            it[TmkVersionsTable.updatedAt] = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        }
        if (updatedRows == 0) error("TMK version not found for tenant=$tenantId version=$version")
    }

    fun findActive(tenantId: String): TmkVersion? = transaction {
        TmkVersionsTable.select { (TmkVersionsTable.tenantId eq tenantId) and (TmkVersionsTable.status eq TmkStatus.ACTIVE) }
            .orderBy(TmkVersionsTable.version, SortOrder.DESC)
            .limit(1)
            .map(::toVersion)
            .singleOrNull()
    }

    fun findVersion(tenantId: String, version: Int): TmkVersion? = transaction {
        TmkVersionsTable.select { (TmkVersionsTable.tenantId eq tenantId) and (TmkVersionsTable.version eq version) }
            .limit(1)
            .map(::toVersion)
            .singleOrNull()
    }

    fun listVersions(tenantId: String): List<TmkVersion> = transaction {
        TmkVersionsTable.select { TmkVersionsTable.tenantId eq tenantId }
            .orderBy(TmkVersionsTable.version, SortOrder.DESC)
            .map(::toVersion)
    }

    private fun nextVersion(tenantId: String): Int {
        val current = TmkVersionsTable.select { TmkVersionsTable.tenantId eq tenantId }
            .orderBy(TmkVersionsTable.version, SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.get(TmkVersionsTable.version)
        return (current ?: 0) + 1
    }

    private fun toVersion(row: org.jetbrains.exposed.sql.ResultRow): TmkVersion = TmkVersion(
        tenantId = row[TmkVersionsTable.tenantId],
        tmkId = row[TmkVersionsTable.tmkId],
        version = row[TmkVersionsTable.version],
        status = row[TmkVersionsTable.status],
        createdAt = row[TmkVersionsTable.createdAt].atOffset(ZoneOffset.UTC).toInstant(),
        updatedAt = row[TmkVersionsTable.updatedAt].atOffset(ZoneOffset.UTC).toInstant()
    )
}
