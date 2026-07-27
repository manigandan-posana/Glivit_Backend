-- Remote immobilisation / locking applied by device commands.
--
-- LOCK, UNLOCK, ENGINE_CUT and ENGINE_RESTORE change how the vehicle may be
-- used, so the effect is recorded on the device itself rather than only in the
-- command log. Fleet state derivation reads these columns, which is what makes
-- a cut vehicle report IMMOBILISED (and zero speed) instead of RUNNING.

ALTER TABLE devices ADD COLUMN immobilised BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN last_command_type VARCHAR(32);
ALTER TABLE devices ADD COLUMN last_command_at TIMESTAMP;
