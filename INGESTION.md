# GPS Ingestion Pipeline

Device-authenticated telemetry ingestion. Trackers/simulators send raw GPS
packets; the backend validates them, stores the raw point, updates the live
snapshot, derives deterministic features, and (after commit) runs asynchronous
AI evaluation. **AI never blocks ingestion** — if Python/Ollama are down, points
are still stored and locations still update.

## 1. Issue a device ingestion token

Admins (permission `manage_devices`) rotate a device's opaque token:

```
POST /api/devices/{deviceId}/ingest-token      (Bearer <user JWT>)
=> { "data": { "deviceId": 12, "ingestToken": "…url-safe token…" } }
```

The token is shown once. Rotating invalidates the previous token.
(Demo seed data ships predictable tokens: `demo-ingest-<IMEI>`.)

## 2. Send a position

```
POST /api/ingest/positions
Header: X-Device-Token: <the device token>
Content-Type: application/json

{
  "latitude": 12.9718,
  "longitude": 77.6412,
  "speedKph": 46,
  "heading": 90,
  "ignitionOn": true,
  "accuracyMeters": 8,
  "odometerKm": 48213,
  "recordedAt": "2026-07-22T10:15:00Z"
}
```

Response:

```
{ "data": { "accepted": true, "duplicate": false, "state": "RUNNING",
            "gpsConfidence": 0.94, "positionId": 9931 } }
```

The tenant, device and vehicle are resolved **server-side from the token** — the
payload can never assert another tenant's identity.

## Pipeline stages

1. Authenticate device by `X-Device-Token` (`PositionIngestService`).
2. Resolve tenant/device/vehicle from the device record.
3. Validate coordinates (reject out-of-range and `0,0`); mark timestamp validity.
4. Compute deterministic features vs. the previous snapshot
   (`GpsFeatureService`): distance, elapsed time, calculated speed, heading
   change, acceleration, GPS confidence, duplicate/out-of-order flags.
5. Drop exact duplicate packets. Persist the raw `Position`.
6. Update `DeviceCurrentPosition` with a derived state
   (RUNNING/IDLE/STOPPED/NO_DATA) — only for in-order valid points.
7. Publish `PositionIngestedEvent` **AFTER_COMMIT**.
8. `PositionIngestListener` → `AiAsyncEvaluatorService` (bounded, isolated pool)
   calls the Python anomaly scorer with the real derived features; a significant
   anomaly is persisted as an `AiEvent`, explained by Qwen (template fallback if
   Ollama is offline), and broadcast over SSE.

## Validation / anti-corruption rules

- Invalid latitude/longitude → `400` (never corrupts the route).
- Missing/blank token or suspended/expired device → `401`.
- Exact duplicate (same place & time) → accepted but not persisted.
- Out-of-order packets are stored in history but do not move the live location.
- Future timestamps beyond 24h are flagged and lower GPS confidence.
