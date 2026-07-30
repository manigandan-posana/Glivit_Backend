-- =====================================================================
-- Glivt GPS platform - tenant management & tenant switching (V8)
-- MySQL 8 / InnoDB / utf8mb4.
--
-- The platform was already multi-tenant (shared schema, mandatory tenant_id
-- discriminator on every business table since V1). This migration adds what
-- tenant *administration* and *switching* need:
--
--   * tenant administrator identity on the tenant row (company name, admin
--     name/email/phone, the provisioned admin user) so the Manage Tenants list
--     can render without N+1 lookups and duplicate admin emails are rejected
--     by the database, not only by application code
--   * tenant_users: the explicit access mapping that authorises a user to act
--     inside a tenant other than their home tenant. A switch request is only
--     honoured when the target tenant appears in this mapping (or the caller is
--     a platform SUPER_ADMIN)
--   * tenant-scoped unique keys that were previously enforced in application
--     code only (geofence name, vehicle registration, user email)
--   * covering indexes for the tenant_id-leading query shapes added here
--
-- GPS IMEI deliberately stays GLOBALLY unique (uk_devices_imei, V1). A physical
-- tracker belongs to exactly one tenant; that is what makes ingestion - which
-- authenticates by IMEI and then derives the tenant from the device row - unable
-- to write a position into the wrong tenant. Scoping IMEI per tenant would make
-- the ingest lookup ambiguous and weaken isolation rather than strengthen it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tenants: administrator identity + separate company name.
--
-- tenants.name is the tenant label shown in the switcher; company_name is the
-- legal/registered company it belongs to. Both are needed by the Manage Tenants
-- screen and they are not always the same string.
-- ---------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN company_name  VARCHAR(160) NULL AFTER name,
    ADD COLUMN admin_name    VARCHAR(160) NULL AFTER company_name,
    ADD COLUMN admin_email   VARCHAR(160) NULL AFTER admin_name,
    ADD COLUMN admin_phone   VARCHAR(32)  NULL AFTER admin_email,
    ADD COLUMN admin_user_id BIGINT       NULL AFTER admin_phone;

-- Pre-existing tenants have no separate company name recorded; the tenant name
-- is the best available value and keeps the column NOT NULL from here on.
UPDATE tenants SET company_name = name WHERE company_name IS NULL;

ALTER TABLE tenants
    MODIFY COLUMN company_name VARCHAR(160) NOT NULL;

-- Tenant name and admin email are unique platform-wide: the Create Tenant form
-- rejects duplicates up front, and these keys make that guarantee real under
-- concurrent creates. MySQL permits many NULLs in a unique index, so tenants
-- provisioned before this migration (admin_email NULL) do not collide.
ALTER TABLE tenants
    ADD UNIQUE KEY uk_tenants_name (name),
    ADD UNIQUE KEY uk_tenants_admin_email (admin_email),
    ADD KEY idx_tenants_status (status),
    ADD KEY idx_tenants_admin_user (admin_user_id);

-- ---------------------------------------------------------------------
-- Tenant <-> user access mapping.
--
-- Authoritative answer to "may this user act inside this tenant?". Every user
-- has exactly one home_tenant row (their users.tenant_id); additional rows grant
-- explicit multi-tenant access. The switch endpoint validates against this table
-- and never against a tenant id supplied by the client.
-- ---------------------------------------------------------------------
CREATE TABLE tenant_users (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    home_tenant TINYINT(1)  NOT NULL DEFAULT 0,
    granted_by  BIGINT      NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_users_user_tenant (user_id, tenant_id),
    KEY idx_tenant_users_tenant (tenant_id),
    KEY idx_tenant_users_user (user_id),
    CONSTRAINT fk_tenant_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tenant_users_user   FOREIGN KEY (user_id)   REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Back-fill the home-tenant mapping for every existing login so nobody loses
-- access to the tenant they are already signed into.
INSERT INTO tenant_users (tenant_id, user_id, home_tenant, created_at)
SELECT u.tenant_id, u.id, 1, NOW(6) FROM users u;

-- ---------------------------------------------------------------------
-- Refresh tokens remember the active tenant.
--
-- Token rotation must preserve the tenant the session is acting inside. Without
-- this column a refresh would re-issue the access token against the user's home
-- tenant, so the app would appear to switch tenants by itself as soon as the
-- short-lived access token expired.
-- ---------------------------------------------------------------------
ALTER TABLE refresh_tokens
    ADD COLUMN active_tenant_id BIGINT NULL AFTER device_info,
    ADD KEY idx_refresh_active_tenant (active_tenant_id);

UPDATE refresh_tokens rt
   JOIN users u ON u.id = rt.user_id
   SET rt.active_tenant_id = u.tenant_id
 WHERE rt.active_tenant_id IS NULL;

-- ---------------------------------------------------------------------
-- Tenant-scoped unique keys.
--
-- These were enforced only by service-layer exists* checks, which cannot stop
-- two concurrent requests. Scoped by tenant_id so two tenants may independently
-- own the same geofence name / vehicle registration / user email.
-- ---------------------------------------------------------------------
ALTER TABLE geofences
    ADD UNIQUE KEY uk_geofences_tenant_name (tenant_id, name);

ALTER TABLE vehicles
    ADD UNIQUE KEY uk_vehicles_tenant_registration (tenant_id, registration_number);

ALTER TABLE users
    ADD UNIQUE KEY uk_users_tenant_email (tenant_id, email);

-- ---------------------------------------------------------------------
-- Tenant-leading covering indexes for the isolation-enforcing query shapes.
-- Every scoped read filters on tenant_id first, so tenant_id must lead.
-- ---------------------------------------------------------------------
ALTER TABLE positions
    ADD KEY idx_positions_tenant_device_time (tenant_id, device_id, device_time);

ALTER TABLE events
    ADD KEY idx_events_tenant_device_time (tenant_id, device_id, server_time);

ALTER TABLE device_commands
    ADD KEY idx_commands_tenant_device (tenant_id, device_id, requested_at);

ALTER TABLE drivers
    ADD KEY idx_drivers_tenant_user (tenant_id, user_id);
