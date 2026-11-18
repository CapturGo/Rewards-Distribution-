# Device Evidence — What Exists vs. What Mobile Must Send

**Status:** Phase B in progress. This document is the contract gap list for
the mobile team.

## Already available in the live captur-api submission response

Verified against production `/v3/api-docs` (`ActivitySubmissionDto`):

| Field | Type | Meaning |
|---|---|---|
| `deviceModel` | string | Device model reported at upload |
| `imuFrequencyHz` | number | IMU sampling rate claimed/recorded |
| `gyroSampleCount` | int | Gyroscope samples captured |
| `accelSampleCount` | int | Accelerometer samples captured |
| `syncTier` | string | Sync tier reported by app |
| `gyroStreamUrl` / `accelStreamUrl` | string | Evidence stream locations |
| `metadataUrls` | object | Additional capture metadata refs |
| `durationSeconds` | int | Clip duration (eligible-duration source TBD) |

The Payments Service now persists these into each reward's `metadata` JSONB
for audit. **They are evidence, not proof** — a client can claim any IMU Hz.

## Missing — requires mobile-app + captur-api changes

Tier rules need per-**recording** proof, not per-device claims:

| Needed field | Why | Current status |
|---|---|---|
| Actual camera FOV used | FULL tier requires 110° wide | Not collected |
| Aspect ratio confirmed (16:9) | All tiers require it | Not collected |
| Frame rate achieved | IPHONE/BASIC require 30fps | Not collected |
| Selected camera lens | Ultrawide vs wide matters for FOV | Not collected |
| Device-signed capability manifest | `deviceSignature` exists on submit but isn't returned in submission DTO | Collected at upload, not exposed |
| Capture-session timestamps | Prove recording window vs upload time | Partial (`createdAt` only) |

## Policy decision needed (product)

Until capture-time evidence exists, the service uses the **device capability
registry** (`device_capabilities` table, admin-managed, `VERIFIED` gate).
This proves the *device model* is capable — it does not prove *this
recording* used those settings. Product must decide whether registry-tier is
acceptable for launch or whether capture-time evidence is mandatory.
