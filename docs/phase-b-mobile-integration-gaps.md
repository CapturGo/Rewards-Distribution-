# Phase B: Mobile App Integration Gap Analysis

## Status: Phase B Complete — Waiting on Mobile Team

### What Phase B Added (Server-Side Only)

Phase B captures and persists the device evidence that **captur-api already returns**
for each submission. No new APIs were created, no eligibility rules changed,
no payment logic was touched.

| Field               | Source          | Persisted In               | Used For              |
|---------------------|-----------------|----------------------------|-----------------------|
| `syncTier`          | captur-api JSON | `submission_device_evidence.sync_tier` + reward `metadata` JSONB | Audit trail |
| `imuFrequencyHz`    | captur-api JSON | `submission_device_evidence.imu_frequency_hz` + reward `metadata` JSONB | Audit trail |
| `gyroSampleCount`   | captur-api JSON | `submission_device_evidence.gyro_sample_count` + reward `metadata` JSONB | Audit trail |
| `accelSampleCount`  | captur-api JSON | `submission_device_evidence.accel_sample_count` + reward `metadata` JSONB | Audit trail |
| `gyroStreamUrl`     | captur-api JSON | `submission_device_evidence.gyro_stream_url` | Audit trail |
| `accelStreamUrl`    | captur-api JSON | `submission_device_evidence.accel_stream_url` | Audit trail |
| `metadataUrls`      | captur-api JSON | `submission_device_evidence.metadata_urls` (JSONB) | Audit trail |
| `metadata`          | captur-api JSON | `submission_device_evidence.raw_metadata` (JSONB) | Full snapshot |

---

### What the Mobile App Does NOT Send Yet

These fields are **needed for future phases** (automated tier classification,
fraud detection, evidence verification) but are **not yet captured** at the
device level during video recording:

| Missing Field           | Why We Need It                                        | Priority  |
|-------------------------|-------------------------------------------------------|-----------|
| **Camera FOV (degrees)**| Verify the camera matches the device spec             | HIGH      |
| **Camera FPS**          | Verify frame rate meets task requirements              | HIGH      |
| **Camera Lens ID**      | Distinguish front/back/wide/ultra-wide                 | MEDIUM    |
| **Aspect Ratio (16:9)** | Confirm correct framing for 3D data                   | MEDIUM    |
| **Gyroscope Model**     | Hardware attestation — is the sensor real?             | HIGH      |
| **Accelerometer Model** | Hardware attestation — is the sensor real?             | HIGH      |
| **IMU Calibration Data**| Drift correction quality metric                        | LOW       |
| **GPS coordinates**     | Geo-fence validation for location-bound tasks          | MEDIUM    |
| **GPS accuracy (m)**    | Filter low-quality location fixes                      | LOW       |
| **Device Serial Hash**  | Unique device fingerprint (privacy-safe hash)          | HIGH      |
| **OS Version**          | Filter rooted/jailbroken devices                       | MEDIUM    |
| **App Build Number**    | Ensure minimum SDK version for telemetry               | LOW       |
| **Hardware Attestation**| Android SafetyNet / iOS DeviceCheck token              | HIGH      |
| **Magnetometer data**   | 9-axis IMU for heading / orientation                   | LOW       |
| **Battery level**       | Detect power-saving modes that throttle sensors        | LOW       |

---

### What This Means for Reward Accuracy

**Today:** The payments service uses the `device_capabilities` registry (admin-managed)
to classify devices into FULL/IPHONE/BASIC tiers. This is a **manual lookup table**.
Phase B captures whatever captur-api returns but does **not use it for tier classification**.

**Future (Phase C/D):** With the missing fields above, we could:
1. Auto-classify tier from real hardware attestation instead of admin lookup
2. Cross-validate IMU sample counts against duration × frequency
3. Flag suspicious submissions where sensor data is missing or inconsistent
4. Geo-fence validation for location-specific tasks

---

### Mobile Team Action Items

1. **Add hardware attestation token** to each submission upload
   - Android: Play Integrity API (successor to SafetyNet)
   - iOS: DeviceCheck + App Attest
2. **Include camera metadata** in the submission payload:
   - FOV, FPS, lens ID, aspect ratio
3. **Include device fingerprint** (hashed serial or equivalent privacy-safe ID)
4. **Transmit OS version and app build number** in submission headers
5. **Ensure IMU stream URLs** are always populated when gyro/accel data is captured

---

### Remaining Blockers Before Devnet Testing

| Blocker                                | Owner   | Status        |
|----------------------------------------|---------|---------------|
| Double-compensation decision (tokens AND/OR USDC?) | Sai     | **PENDING**   |
| Devnet treasury wallet keypair         | You     | **NOT CREATED** |
| Devnet USDC mint / faucet              | You     | **NOT CREATED** |
| Mobile hardware attestation            | Mobile  | **NOT STARTED** |
| `SOLANA_ENABLED=true` on devnet        | Ops     | **BLOCKED** (needs above) |
| Dashboard payouts page (Phase D)       | Frontend| **NOT STARTED** |
