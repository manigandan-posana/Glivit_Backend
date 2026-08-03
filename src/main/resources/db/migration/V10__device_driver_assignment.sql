-- ---------------------------------------------------------------------
-- Link driver ID directly to device/vehicle assignment.
--
-- Previously authored as V9, which collided with the already-applied
-- "role based access" V9. Moved here so applied history stays intact.
--
-- Guarded so re-running is a no-op; MySQL has no ADD COLUMN IF NOT EXISTS.
-- Purely additive -- no existing row is touched.
-- ---------------------------------------------------------------------

SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'devices'
             AND COLUMN_NAME  = 'driver_id'),
    'SELECT 1',
    'ALTER TABLE devices ADD COLUMN driver_id BIGINT NULL AFTER manager_id'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'devices'
             AND INDEX_NAME   = 'idx_devices_driver'),
    'SELECT 1',
    'ALTER TABLE devices ADD KEY idx_devices_driver (driver_id)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
