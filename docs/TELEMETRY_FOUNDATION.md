# Glivit — GPS Telemetry Foundation (Stage 1 increment)

This document covers the telemetry-foundation work landed on branch
`claude/glivit-ai-fleet-intelligence-o9o61i`. Per the platform brief's critical
rule ("do not begin AI screens before completing the GPS data foundation"),
this is the load-bearing prerequisite for every later AI feature. AI features,
the trip engine, live streaming and the frontend integration are **not** part of
this increment and are listed under "Remaining work" below.

## What landed

### 1. Real position ingestion pipeline (§3.1)
- `POST /api/telemetry/positions` — single position.
- `POST /api/telemetry/batch` — batch of positions.
- **Device-authenticated, not user-JWT.** A GPS device / protocol gateway
  presents its IMEI + rotating `device_token` (via `X-Device-Imei` /
  `X-Device-Token` headers or in the JSON body). The tenant is resolved from the
  device record — **never** accepted from the caller.
- Ingestion flow: authenticate device → reject unknown / wrong-token (401),
  suspended / expired (403) → validate lat/lon/speed/time → normalise server
  time to UTC (device time kept separately) → idempotent persist to append-only
  `positions` → atomic `device_current_position` upsert (out-of-order packets
  never move the snapshot backwards) → backend-derived `DeviceState`.
- **Idempotency**: `positions.dedup_key` unique per device. The device may send
  a `messageId`; otherwise a stable key is derived from device time so
  re-transmissions do not create duplicate history. A DB unique-constraint race
  is caught and treated as a duplicate.
- No AI / notification dependency in the path: ingestion cannot fail because a
  downstream model or push service is down.

### 2. Backend-owned device state (§3.2)
- `DeviceStateCalculator` derives state deterministically from the latest
  reading and per-tenant thresholds. The mobile client only renders it.
- States: `RUNNING` (moving), `IDLE`, `STOPPED`, `OFFLINE`, `NO_DATA`,
  `GPS_INVALID`, `POWER_DISCONNECTED`, `EXPIRED`, `SUSPENDED` (plus legacy
  `INACTIVE`). `RUNNING` retained as the "moving" name for backward
  compatibility with existing seed data / clients.
- Thresholds are per-tenant and configurable (`tenant_telemetry_settings`):
  offline timeout, no-data timeout, idle-speed, min-stop, overspeed, GPS-valid
  requirement, power-min voltage. Absent row → application defaults.

### 3. Position history & playback (§3.3, partial)
- `GET /api/devices/{deviceId}/positions` — paginated, bounded date range,
  newest-first.
- `GET /api/devices/{deviceId}/playback` — simplified route geometry (decimated
  to ≤1500 points, first/last preserved), per-point speed/ignition/GPS validity
  (so speed & ignition timelines need no extra call), event markers, deterministic
  stop markers, and total distance (haversine).
- Both enforce device ownership (IDOR → 404 out of scope) and a max range from
  the tenant's `max_history_days`.

### 4. Existing bugs fixed (§4)
- **§4.1 Suspended-device filtering** — suspended devices are excluded from the
  device list by default; `?includeSuspended=true` opts them back in.
- **§4.2 Device expiry** — effective expiry is derived from the expiry date in
  the device's own timezone (`DeviceExpiry`); a passed date reads as `EXPIRED`
  even if an operator never flipped the status. Applied consistently to the
  device list, device detail, dashboard counts and ingestion rejection.
- **Dashboard device-first counting (§3.2)** — the summary now counts *every*
  device via a device-first left join to the current-position table, so devices
  that have never reported are counted as `NO_DATA` instead of vanishing.

## API quick reference

```http
POST /api/telemetry/positions        # headers X-Device-Imei / X-Device-Token, or body imei/token
POST /api/telemetry/batch
GET  /api/devices/{deviceId}/positions?from=<iso>&to=<iso>&page=0&size=100
GET  /api/devices/{deviceId}/playback?from=<iso>&to=<iso>
GET  /api/devices?includeSuspended=false      # suspended hidden by default
GET  /api/dashboard/summary                   # device-first counts
```

Ingest example:

```bash
curl -X POST http://localhost:8085/api/telemetry/positions \
  -H 'Content-Type: application/json' \
  -H 'X-Device-Imei: 900000000000001' \
  -H 'X-Device-Token: <device_token>' \
  -d '{"latitude":12.97,"longitude":77.59,"speed":42,"ignition":true,"gpsValid":true,"messageId":"pkt-1"}'
```

## Schema changes (Flyway `V3__telemetry_foundation.sql`)
- `devices`: `device_token` (unique, auto-generated per device), `last_packet_at`.
- `positions`: `dedup_key` + unique `(device_id, dedup_key)`.
- `device_current_position`: widened `state` to `VARCHAR(24)`; added
  `external_power`, `battery`, `fuel_level`, `satellites`, `network_signal`.
- new `tenant_telemetry_settings` (per-tenant thresholds).

> Tests run on H2 with `ddl-auto=create-drop` (entities drive the test schema);
> the MySQL Flyway DDL is exercised against MySQL in deployment.

## Tests added
- `TelemetryIngestTest` — moving/stopped state derivation, unknown-device 401,
  wrong-token 401, suspended 403, expired 403, null-island 400, idempotent
  de-duplication, batch tolerating an invalid row.
- `DeviceStateApiTest` — suspended filtering (default vs `includeSuspended`),
  effective-expiry state, dashboard device-first `NO_DATA` counting, playback
  simplification + stop detection.

## Remaining work (not in this increment)
Deterministic trip/stop engine (§3.4), live WebSocket/SSE streaming (§3.5),
refresh-token revalidation (§4.4), report parameter persistence & real report
types (§4.5), the AI provider abstraction and all AI features (§5–§29), and the
entire frontend integration (§30+). These are intentionally left as follow-ups
rather than shipped as empty skeletons.
