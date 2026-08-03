-- ---------------------------------------------------------------------
-- Role based access.
--
-- Reconstructed to match the V9 that is already recorded in
-- flyway_schema_history (description "role based access"). The original
-- script was authored in a working copy that no longer exists; this file
-- reproduces exactly the schema delta that migration left behind, so a
-- database built from scratch ends up identical to one that already ran it.
--
-- Every statement is guarded, so re-running this script is a no-op.
-- MySQL has no ADD COLUMN IF NOT EXISTS, hence the information_schema
-- guards below.
-- ---------------------------------------------------------------------

-- Widen the role column: VARCHAR(16) from V1 was too tight for the
-- role identifiers used by role based access.
ALTER TABLE users MODIFY COLUMN role VARCHAR(32) NOT NULL;

-- users.must_change_password -- force a password rotation on first login.
SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'users'
             AND COLUMN_NAME  = 'must_change_password'),
    'SELECT 1',
    'ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- users.password_changed_at -- last successful password rotation.
SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'users'
             AND COLUMN_NAME  = 'password_changed_at'),
    'SELECT 1',
    'ALTER TABLE users ADD COLUMN password_changed_at DATETIME(6) NULL AFTER must_change_password'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tenants.system_tenant -- marks the platform-owned tenant.
SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'tenants'
             AND COLUMN_NAME  = 'system_tenant'),
    'SELECT 1',
    'ALTER TABLE tenants ADD COLUMN system_tenant BOOLEAN NOT NULL DEFAULT FALSE AFTER status'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'tenants'
             AND INDEX_NAME   = 'idx_tenants_system'),
    'SELECT 1',
    'ALTER TABLE tenants ADD KEY idx_tenants_system (system_tenant)'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
