-- =====================================================================
-- Glivt GPS platform - telemetry foundation (V3)
-- MySQL 8 / InnoDB / utf8mb4.
--
-- Adds the production telemetry ingestion foundation:
--   * per-device ingestion credential (device_token) and last-packet marker
--   * idempotency key on the append-only positions history
--   * a wider derived-state column (new POWER_DISCONNECTED / GPS_INVALID /
--     OFFLINE / SUSPENDED states) plus richer current-position diagnostics
--   * per-tenant, configurable telemetry thresholds used by the backend
--     device-state calculator (never inferred on the mobile client)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Devices: ingestion credential + last packet marker.
-- device_token authenticates a GPS device (or its protocol gateway) when
-- posting positions. It is a rotating secret, never a device credential dump.
-- ---------------------------------------------------------------------
ALTER TABLE devices
    ADD COLUMN device_token   VARCHAR(80) NULL AFTER imei,
    ADD COLUMN last_packet_at DATETIME(6) NULL AFTER status;

-- Back-fill a token for every existing device so provisioned trackers keep
-- ingesting after upgrade. Application code rotates these on demand.
UPDATE devices
   SET device_token = REPLACE(UUID(), '-', '')
 WHERE device_token IS NULL;

ALTER TABLE devices
    ADD UNIQUE KEY uk_devices_token (device_token),
    ADD KEY idx_devices_last_packet (tenant_id, last_packet_at);

-- ---------------------------------------------------------------------
-- Positions: idempotency key so retried / re-transmitted packets do not
-- create duplicate history rows. NULL keys are allowed (legacy inserts).
-- ---------------------------------------------------------------------
ALTER TABLE positions
    ADD COLUMN dedup_key VARCHAR(120) NULL AFTER attributes,
    ADD UNIQUE KEY uk_positions_device_dedup (device_id, dedup_key);

-- ---------------------------------------------------------------------
-- Current position: widen derived-state column for the new states and add
-- diagnostics columns consumed by device-health / self-healing features.
-- ---------------------------------------------------------------------
ALTER TABLE device_current_position
    MODIFY COLUMN state VARCHAR(24) NOT NULL DEFAULT 'NO_DATA',
    ADD COLUMN external_power DOUBLE NULL AFTER gps_valid,
    ADD COLUMN battery        DOUBLE NULL AFTER external_power,
    ADD COLUMN fuel_level     DOUBLE NULL AFTER battery,
    ADD COLUMN satellites     INT    NULL AFTER fuel_level,
    ADD COLUMN network_signal INT    NULL AFTER satellites;

-- ---------------------------------------------------------------------
-- Per-tenant telemetry thresholds. Absent row => application defaults.
-- Drives the backend DeviceStateCalculator and overspeed evaluation.
-- ---------------------------------------------------------------------
CREATE TABLE tenant_telemetry_settings (
    tenant_id              BIGINT      NOT NULL,
    offline_timeout_seconds INT        NOT NULL DEFAULT 900,
    no_data_timeout_seconds INT        NOT NULL DEFAULT 3600,
    idle_speed_kmh         DOUBLE      NOT NULL DEFAULT 3,
    min_stop_minutes       INT         NOT NULL DEFAULT 5,
    overspeed_kmh          DOUBLE      NOT NULL DEFAULT 80,
    require_gps_valid      TINYINT(1)  NOT NULL DEFAULT 1,
    power_min_voltage      DOUBLE      NOT NULL DEFAULT 1,
    created_at             DATETIME(6) NOT NULL,
    updated_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_telemetry_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
