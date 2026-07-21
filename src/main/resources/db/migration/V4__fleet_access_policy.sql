-- =====================================================================
-- Glivt - fleet access policy assignment model (V4)
-- MySQL 8 / InnoDB / utf8mb4.
--
-- Closes the driver IDOR: a driver could previously reach any device in the
-- same tenant by id because only tenant scope was checked. These tables let
-- FleetAccessPolicy resolve the set of devices a non-admin user may reach:
--   * drivers.user_id            - links a driver record to a login
--   * vehicle_driver_assignments - which driver currently drives which vehicle
--   * user_project_assignments   - project-scoped (non-admin) users
-- Device access then flows user -> driver -> vehicle -> device, or
-- user -> project -> device.
-- =====================================================================

-- Link a driver record to its user login (nullable: not every driver has one).
ALTER TABLE drivers
    ADD COLUMN user_id BIGINT NULL AFTER tenant_id,
    ADD KEY idx_drivers_user (user_id),
    ADD CONSTRAINT fk_drivers_user FOREIGN KEY (user_id) REFERENCES users (id);

-- Vehicle <-> driver assignments (historical + current).
CREATE TABLE vehicle_driver_assignments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    vehicle_id  BIGINT       NOT NULL,
    driver_id   BIGINT       NOT NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1,
    source      VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    assigned_by BIGINT       NULL,
    start_time  DATETIME(6)  NOT NULL,
    end_time    DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vda_tenant (tenant_id),
    KEY idx_vda_driver_active (driver_id, active),
    KEY idx_vda_vehicle_active (vehicle_id, active),
    CONSTRAINT fk_vda_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_vda_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT fk_vda_driver  FOREIGN KEY (driver_id)  REFERENCES drivers (id),
    CONSTRAINT fk_vda_by      FOREIGN KEY (assigned_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Project-scoped users (a non-admin user limited to specific projects).
CREATE TABLE user_project_assignments (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    project_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_upa_user_project (user_id, project_id),
    KEY idx_upa_tenant (tenant_id),
    KEY idx_upa_user (user_id),
    CONSTRAINT fk_upa_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_upa_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_upa_project FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
