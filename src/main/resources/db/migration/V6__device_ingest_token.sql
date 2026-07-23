-- =====================================================================
-- Per-device ingestion credential. GPS trackers authenticate to the
-- ingestion endpoint with this opaque token; the tenant/device/vehicle are
-- resolved server-side from it and never trusted from the payload.
-- =====================================================================

ALTER TABLE devices ADD COLUMN ingest_token VARCHAR(80) NULL;

-- Unique where present; MySQL allows multiple NULLs under a unique index.
CREATE UNIQUE INDEX uk_devices_ingest_token ON devices (ingest_token);
