package com.capturgo.payments.health

import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val dataSource: DataSource) {

  @GetMapping("/health")
  fun health(): Map<String, String> = mapOf(
    "status" to "UP",
    "service" to "captur-payments",
    "timestamp" to Instant.now().toString(),
  )

  @GetMapping("/ready")
  fun ready(): Map<String, Any> {
    return try {
      dataSource.connection.use { conn ->
        conn.createStatement().use { stmt -> stmt.executeQuery("SELECT 1") }
      }
      mapOf("status" to "READY", "database" to "UP", "timestamp" to Timestamp.from(Instant.now()).toString())
    } catch (e: Exception) {
      mapOf("status" to "NOT_READY", "database" to "DOWN", "error" to (e.message ?: "unknown"))
    }
  }
}
