package com.capturgo.payments.api.admin

import com.capturgo.payments.core.auth.AuthContext
import com.capturgo.payments.core.auth.AuthPrincipal
import com.capturgo.payments.core.exception.UnauthorizedException
import com.capturgo.payments.rewards.DeviceCapability
import com.capturgo.payments.rewards.DeviceCapabilityRepository
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DeviceUpsertRequest(
  val deviceKey: String,
  val deviceModel: String? = null,
  val tier: String,
  val hasGyroscope: Boolean? = null,
  val hasAccelerometer: Boolean? = null,
  val imuFrequencyHz: Int? = null,
  val cameraFovDegrees: Int? = null,
  val aspectRatio169: Boolean? = null,
  val fps: Int? = null,
  val verification: String = "VERIFIED",
  val source: String = "MANUAL",
  val notes: String? = null,
)

@RestController
@RequestMapping("/api/v1/admin/devices")
class AdminDeviceController(private val repo: DeviceCapabilityRepository) {

  private fun principal(req: HttpServletRequest): AuthPrincipal =
    req.getAttribute(AuthContext.PRINCIPAL_ATTRIBUTE) as? AuthPrincipal
      ?: throw UnauthorizedException()

  @GetMapping
  fun list(): Map<String, Any> = mapOf("items" to repo.listAll())

  @PostMapping
  fun upsert(@RequestBody body: DeviceUpsertRequest, req: HttpServletRequest): ResponseEntity<DeviceCapability> {
    principal(req) // auth enforced by filter; audit logged via repository update
    repo.upsert(
      deviceKey = body.deviceKey.trim().lowercase(),
      deviceModel = body.deviceModel,
      tier = body.tier.uppercase(),
      hasGyro = body.hasGyroscope,
      hasAccel = body.hasAccelerometer,
      imuHz = body.imuFrequencyHz,
      fov = body.cameraFovDegrees,
      ar169 = body.aspectRatio169,
      fps = body.fps,
      verification = body.verification.uppercase(),
      source = body.source.uppercase(),
      notes = body.notes,
    )
    val saved = repo.findByDeviceKey(body.deviceKey.trim().lowercase())
      ?: throw IllegalStateException("device vanished after upsert")
    return ResponseEntity.status(HttpStatus.CREATED).body(saved)
  }
}
