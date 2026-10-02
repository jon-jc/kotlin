package com.roam.server

import com.roam.core.Catalog
import com.roam.core.api.ApiStay
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val wireJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}

class Database(url: String, user: String, password: String) : AutoCloseable {
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 6
                connectionTimeout = 10_000
                validationTimeout = 3_000
                connectionInitSql = "SET search_path TO roam"
                poolName = "roam-db"
            }
        )

    init {
        migrate()
    }

    fun <T> transaction(block: (Connection) -> T): T {
        repeat(3) { attempt ->
            try {
                pool.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result = block(connection)
                        connection.commit()
                        return result
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState !in setOf("40001", "40P01") || attempt == 2) throw e
            }
        }
        error("Transaction retry exhausted")
    }

    private fun migrate() = transaction { c ->
        c.exec("SELECT pg_advisory_xact_lock(721319405)")
        c.exec("CREATE SCHEMA IF NOT EXISTS roam")
        c.exec("REVOKE ALL ON SCHEMA roam FROM PUBLIC")
        c.exec("SET LOCAL search_path TO roam")
        c.exec(
            "CREATE TABLE IF NOT EXISTS schema_migrations (version integer PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())"
        )
        val sql =
            javaClass.getResourceAsStream("/db/001_initial.sql")!!.bufferedReader().use {
                it.readText()
            }
        val checksum =
            MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val previous =
            c.one("SELECT checksum FROM schema_migrations WHERE version=1") { it.getString(1) }
        if (previous == null) {
            c.exec(sql)
            c.exec("INSERT INTO schema_migrations(version,checksum) VALUES (1,?)", checksum)
        } else
            check(previous == checksum) {
                "Database migration checksum differs from the deployed version"
            }
    }

    fun healthy() = transaction { c -> c.one("SELECT 1") { it.getInt(1) } == 1 }

    /**
     * Explicit development command only. All records remain visibly fictional and reject live
     * payments.
     */
    fun seedDemo() = transaction { c ->
        Catalog.stays.forEach { stay ->
            val zone =
                when (stay.id) {
                    "kyoto" -> "Asia/Tokyo"
                    "alpine" -> "Europe/Rome"
                    else -> "Europe/Lisbon"
                }
            val dto =
                ApiStay(
                    stay.id,
                    stay.name,
                    stay.location,
                    stay.country,
                    stay.category,
                    stay.nightly.minor.toString(),
                    stay.maxGuests,
                    stay.image,
                    stay.description,
                    stay.amenities,
                    zone,
                    true,
                )
            c.exec(
                "INSERT INTO stays(id,payload,active) VALUES (?,?::jsonb,true) ON CONFLICT(id) DO NOTHING",
                stay.id,
                wireJson.encodeToString(dto),
            )
        }
    }

    override fun close() = pool.close()
}

internal fun Connection.exec(sql: String, vararg values: Any?): Int =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        if (statement.execute()) 0 else statement.updateCount
    }

internal fun <T> Connection.rows(
    sql: String,
    vararg values: Any?,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList { while (result.next()) add(read(result)) }
        }
    }

internal fun <T> Connection.one(sql: String, vararg values: Any?, read: (ResultSet) -> T): T? =
    rows(sql, *values, read = read).firstOrNull()
