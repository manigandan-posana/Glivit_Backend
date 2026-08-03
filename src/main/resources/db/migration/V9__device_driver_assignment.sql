-- Link driver ID directly to device/vehicle assignment.
ALTER TABLE devices ADD COLUMN driver_id BIGINT NULL AFTER manager_id;
ALTER TABLE devices ADD KEY idx_devices_driver (driver_id);
