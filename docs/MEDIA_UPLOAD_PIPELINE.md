# Media Upload Pipeline — Problem & Proposed Solution

Date: 2026-11-27
Status: Proposal (not implemented)

## Problem

Today, every photo/video upload from the mobile app flows **through the API
server** before reaching storage:

```
Phone → POST /api/v1/media/{type}/upload (multipart FormData)
      → captur-api / captur-go-api server buffers the file
      → writes to GCS bucket
      → returns a filename the app stores on the submission/POI/report
```

This works, but it has real scaling and reliability costs:

- **API server is in the data path.** Every upload consumes Cloud Run
  CPU/memory and bandwidth. Large videos (up to the app's ~50MB batch limit)
  tie up request handlers; concurrent uploads can starve the API.
- **No timeout resilience.** If the connection drops mid-upload, the whole
  file is re-sent. There is no resumable upload.
- **No processing step.** Files are stored exactly as uploaded — no resize,
  transcode, thumbnail, watermark, or AI analysis. Thumbnails/previews are
  generated client-side or not at all.
- **No CDN.** Media is served back through the API
  (`GET /media/{type}/{filename}` or `/stream`), which means every image view
  also hits the backend and requires auth headers.
- **Cost.** Cloud Run bills for the bytes it proxies; direct-to-GCS uploads
  are cheaper and faster for the user.

## Current state (verified)

### Mobile app (`MOBILE V2 APP`)

- `src/common/services/mediaService.ts` → `uploadImage()` and
  `uploadMultipleImages()` POST multipart `FormData` via `ApiService.upload()`
  to `/api/v1/media/{type}/upload` and `/api/v1/media/{type}/images/upload`.
- Local preprocessing only: JPEG normalize, compress 0.9; 10MB single /
  ~50MB batch limits.
- Retrieval: `getAuthenticatedSource()` builds authenticated URLs against
  the API (`/media/{type}/{filename}`).
- **The app does NOT use signed URLs today.**

### Backend (`captur-go-api`, `New Backend`)

Both upload styles are already implemented (`cmd/api/main.go`, media routes):

| Route | Purpose |
|---|---|
| `POST /api/v1/media/{type}/upload-url` | Single V4 pre-signed PUT URL (15 min) |
| `POST /api/v1/media/{type}/upload-urls` | Batch pre-signed PUT URLs |
| `POST /api/v1/media/{type}/upload` | Legacy multipart proxy upload (what the app uses) |
| `POST /api/v1/media/{type}/images/upload` | Legacy batch proxy upload |
| `GET /api/v1/media/{type}/{filename}` | Authenticated download |
| `GET /api/v1/media/{type}/{filename}/stream` | Streaming download |
| `GET /api/v1/media/{type}/{filename}/url` | Signed GET URL |
| `DELETE /api/v1/media/{type}/{filename}` | Delete object |

Implementation: `internal/media/handler.go` — GCS `SignedURL` (V4),
`allowedUploadTypes` whitelist per media category, bucket from
`GCS_MEDIA_BUCKET` env.

**So the first step of the target architecture (direct-to-GCS upload) is
already built on the backend — only the app needs to switch.**

## Proposed solution — serverless media pipeline

```
┌─────────────┐   signed PUT    ┌──────────────────┐
│  Mobile app │ ──────────────► │  GCS ingest      │
│ (MediaService│  direct upload  │  bucket          │
│  uses        │                 └────────┬─────────┘
│  upload-url) │                          │ object finalize
└─────────────┘                           ▼
                                 ┌──────────────────┐
                                 │ Pub/Sub topic    │
                                 │ (GCS notification│
                                 └────────┬─────────┘
                                          ▼
                                 ┌──────────────────┐
                                 │ Cloud Run Job /  │  resize, transcode
                                 │ worker (FFmpeg,  │  (H.264/HLS),
                                 │ thumbnails, AI)  │  watermark, scan
                                 └────────┬─────────┘
                                          ▼
                                 ┌──────────────────┐
                                 │ GCS processed    │──► CDN / public URLs
                                 │ bucket           │
                                 └────────┬─────────┘
                                          ▼
                                 ┌──────────────────┐
                                 │ Cloud SQL        │  status, variants,
                                 │ (media table)    │  metadata, URLs
                                 └──────────────────┘
```

### Components

1. **Signed URL upload (app + backend — mostly done)**
   - App calls `POST /api/v1/media/{type}/upload-url(s)` → receives
     `{ uploadUrl, objectKey, publicUrl? }`.
   - App `PUT`s/`POST`s bytes **directly to GCS** (RN `FileSystem.uploadAsync`
     or `fetch` with the signed URL; GCS V4 PUT accepts the file body).
   - App then submits the returned `objectKey`/filename on the
     activity/POI/report payload — same contract as today.
   - Backend change needed: confirm the submit handlers accept the new key
     format, and optionally record an "upload pending" state.

2. **Event trigger**
   - GCS bucket notification → Pub/Sub topic on `OBJECT_FINALIZE`.
   - Filter by prefix (e.g. `uploads/`) to ignore processed outputs.

3. **Processing worker**
   - Cloud Run Job (or Eventarc-triggered Cloud Run service) subscribing to
     the topic.
   - Jobs per media type:
     - Images: resize variants (thumbnail, web, original), strip EXIF/GPS if
       desired, format normalization (WebP/AVIF optional).
     - Video: FFmpeg transcode to adaptive HLS or at least a web-optimized
       MP4 + poster frame.
     - Optional: moderation/AI checks (reuse existing QA pipeline hooks).
   - Idempotent: skip objects already processed (check marker object or DB
     status), tolerate Pub/Sub redelivery.

4. **Output storage + metadata**
   - Processed variants to a separate bucket behind CDN
     (Cloud CDN or signed URLs if content must stay private).
   - `media` metadata table (or extend existing columns): object key, status
     (`UPLOADING/PROCESSING/READY/FAILED`), variant URLs, duration,
     dimensions, error info.
   - App polls status or receives update via existing submission flow;
     dashboard shows processing state.

## User experience & backend improvements

### User experience

| Today | With the pipeline |
|---|---|
| Upload goes phone → API server → GCS (slow on bad networks, can timeout) | Upload goes **directly phone → GCS** via signed URL — faster, better on weak connections |
| Upload fails near the end → restart from zero | Signed URLs + object keys allow **resumable/retry** uploads |
| Videos stored raw — huge files, slow buffering, heavy data use | Worker transcodes to **web-optimized MP4 + HLS** — smooth streaming, less data burned |
| Images served full-size everywhere | Auto-generated **thumbnails** — lists and feeds load instantly |
| App shows nothing while media processes | `status` field → app shows "processing…" then the final media |
| ~50MB batch cap enforced by API body limits | Direct-to-GCS removes the API payload ceiling — larger clips possible |

### Backend / ops

| Today | With the pipeline |
|---|---|
| API server proxies every byte — Cloud Run CPU/bandwidth scales with uploads; heavy uploads can starve other endpoints | API only issues signed URLs (tiny requests) — near-zero media bandwidth on Cloud Run |
| Concurrent video uploads can overwhelm a single instance | Processing isolated in **Cloud Run Jobs** — scales per file, can't take down the API |
| No way to regenerate/fix media after upload | Worker can **reprocess** any object (new variants, re-transcode) without user re-uploads |
| Every media view hits the authenticated API | CDN-backed processed bucket → fast global delivery, cheaper egress |
| No visibility into media health | DB tracks status/variants/errors → dashboard shows failures, automatic retries |

**Expected impact:** Phase 1 (presigned uploads) alone gives users faster,
more reliable uploads — the biggest UX win for the least work, and the
backend is already built. Transcoding/thumbnails (phases 3-4) mainly improve
*playback* experience and infrastructure cost. It's a scale/reliability fix
users feel as "the app just works better," not a named feature.

## Migration plan

| Phase | Work | Risk |
|---|---|---|
| 0 | Doc + decision (this file) | — |
| 1 | App switches to `upload-url` presigned PUT; backend accepts new keys | Low — endpoints exist; keep legacy routes during transition |
| 2 | GCS notifications + Pub/Sub topic + empty worker that just copies/marks READY | Low — proves pipeline, no media changes yet |
| 3 | Image variants (thumb/web) + DB status fields | Medium — needs dashboard/app display updates |
| 4 | Video transcode (FFmpeg) + HLS + poster frames | Medium-High — CPU-heavy, needs Cloud Run Job sizing |
| 5 | CDN + retire proxy upload routes | Low after soak period |

## Open questions

- Do processed videos need adaptive bitrate/HLS, or is a single
  web-optimized MP4 + thumbnail enough? (Cost/complexity driver.)
- Should media stay private (signed GET URLs — current behavior) or become
  CDN-public? Privacy/legal implications for contributor footage.
- Does EXIF/GPS metadata need stripping before delivery to clients?
- Is there a moderation requirement *before* media becomes viewable
  (relevant to QA workflow)?
- Bucket layout: single bucket with prefixes vs. separate ingest/processed
  buckets (recommend separate — simpler lifecycle rules + CDN).

## References

- App upload code: `MOBILE V2 APP/src/common/services/mediaService.ts`
- Backend media handler: `New Backend/internal/media/handler.go`
- Route registration: `New Backend/cmd/api/main.go` (media block ~L239-247)
- Config: `GCS_MEDIA_BUCKET` env on captur-go-api
