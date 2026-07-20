-- =====================================================================
-- Glivt GPS platform - baseline schema (V1)
-- MySQL 8 / InnoDB / utf8mb4. Multi-tenant (shared schema, tenant_id
-- discriminator). All business tables carry tenant_id and are indexed
-- for tenant isolation and the query patterns in the product brief.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tenants (white-label configuration, resolved by company_code)
-- ---------------------------------------------------------------------
CREATE TABLE tenants (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    company_code        VARCHAR(64)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    app_name            VARCHAR(120) NOT NULL,
    logo_url            VARCHAR(512) NULL,
    splash_image_url    VARCHAR(512) NULL,
    primary_color       VARCHAR(9)   NOT NULL DEFAULT '#27D34D',
    secondary_color     VARCHAR(9)   NOT NULL DEFAULT '#2A91BD',
    support_phone       VARCHAR(32)  NULL,
    support_email       VARCHAR(160) NULL,
    privacy_policy_url  VARCHAR(512) NULL,
    terms_url           VARCHAR(512) NULL,
    enabled_modules     TEXT         NULL,
    payment_enabled     TINYINT(1)   NOT NULL DEFAULT 0,
    report_restrictions TEXT         NULL,
    max_history_days    INT          NOT NULL DEFAULT 90,
    min_app_version     VARCHAR(32)  NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenants_company_code (company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Users (Super Admin / Admin / Driver + granular permission JSON)
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    username        VARCHAR(120) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    email           VARCHAR(160) NULL,
    mobile          VARCHAR(32)  NULL,
    address         VARCHAR(512) NULL,
    role            VARCHAR(16)  NOT NULL,
    manager_id      BIGINT       NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    account_expiry  DATETIME(6)  NULL,
    permissions     TEXT         NULL,
    fcm_token       VARCHAR(512) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_tenant_username (tenant_id, username),
    KEY idx_users_tenant (tenant_id),
    KEY idx_users_manager (manager_id),
    CONSTRAINT fk_users_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_users_manager FOREIGN KEY (manager_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Refresh tokens (rotation + revocation). Only hashes are stored.
-- ---------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    revoked      TINYINT(1)   NOT NULL DEFAULT 0,
    replaced_by  VARCHAR(128) NULL,
    device_info  VARCHAR(256) NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_user (user_id),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Projects
-- ---------------------------------------------------------------------
CREATE TABLE projects (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    name        VARCHAR(160) NOT NULL,
    description VARCHAR(512) NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_projects_tenant (tenant_id),
    CONSTRAINT fk_projects_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Device groups (hierarchical)
-- ---------------------------------------------------------------------
CREATE TABLE device_groups (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT       NOT NULL,
    name       VARCHAR(160) NOT NULL,
    parent_id  BIGINT       NULL,
    manager_id BIGINT       NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_groups_tenant (tenant_id),
    KEY idx_groups_parent (parent_id),
    CONSTRAINT fk_groups_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_groups_parent FOREIGN KEY (parent_id) REFERENCES device_groups (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Drivers
-- ---------------------------------------------------------------------
CREATE TABLE drivers (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT       NOT NULL,
    project_id        BIGINT       NULL,
    name              VARCHAR(160) NOT NULL,
    identifier        VARCHAR(64)  NULL,
    phone             VARCHAR(32)  NULL,
    licence_number    VARCHAR(64)  NULL,
    licence_expiry    DATE         NULL,
    emergency_contact VARCHAR(32)  NULL,
    active            TINYINT(1)   NOT NULL DEFAULT 1,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_drivers_tenant (tenant_id),
    KEY idx_drivers_project (project_id),
    CONSTRAINT fk_drivers_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_drivers_project FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Vehicles (physical asset; distinct from the GPS device)
-- ---------------------------------------------------------------------
CREATE TABLE vehicles (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    project_id          BIGINT       NULL,
    driver_id           BIGINT       NULL,
    name                VARCHAR(160) NOT NULL,
    registration_number VARCHAR(64)  NULL,
    category            VARCHAR(32)  NOT NULL DEFAULT 'CAR',
    odometer            DOUBLE       NOT NULL DEFAULT 0,
    engine_hours        DOUBLE       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vehicles_tenant (tenant_id),
    KEY idx_vehicles_project (project_id),
    KEY idx_vehicles_driver (driver_id),
    CONSTRAINT fk_vehicles_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_vehicles_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_vehicles_driver  FOREIGN KEY (driver_id)  REFERENCES drivers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- GPS devices (IMEI-unique tracker). Linked 1:1 to a vehicle when active.
-- ---------------------------------------------------------------------
CREATE TABLE devices (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    project_id    BIGINT       NULL,
    group_id      BIGINT       NULL,
    vehicle_id    BIGINT       NULL,
    manager_id    BIGINT       NULL,
    imei          VARCHAR(64)  NOT NULL,
    name          VARCHAR(160) NOT NULL,
    sim_number    VARCHAR(32)  NULL,
    model         VARCHAR(64)  NULL,
    port          INT          NULL,
    category      VARCHAR(32)  NOT NULL DEFAULT 'GPS',
    driver_name   VARCHAR(160) NULL,
    driver_phone  VARCHAR(32)  NULL,
    remarks       VARCHAR(512) NULL,
    address       VARCHAR(512) NULL,
    sim_provider  VARCHAR(64)  NULL,
    sim_apn       VARCHAR(64)  NULL,
    expiry_date   DATE         NULL,
    activated_at  DATE         NULL,
    timezone      VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    distance_unit VARCHAR(8)   NOT NULL DEFAULT 'KM',
    speed_unit    VARCHAR(8)   NOT NULL DEFAULT 'KMH',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_devices_imei (imei),
    UNIQUE KEY uk_devices_active_vehicle (vehicle_id),
    KEY idx_devices_tenant (tenant_id),
    KEY idx_devices_project (project_id),
    KEY idx_devices_group (group_id),
    KEY idx_devices_expiry (expiry_date),
    CONSTRAINT fk_devices_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_devices_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_devices_group   FOREIGN KEY (group_id)   REFERENCES device_groups (id),
    CONSTRAINT fk_devices_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT fk_devices_manager FOREIGN KEY (manager_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Positions (append-only history). Late/out-of-order packets kept here.
-- ---------------------------------------------------------------------
CREATE TABLE positions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    device_id      BIGINT       NOT NULL,
    vehicle_id     BIGINT       NULL,
    latitude       DOUBLE       NOT NULL,
    longitude      DOUBLE       NOT NULL,
    altitude       DOUBLE       NULL,
    accuracy       DOUBLE       NULL,
    speed          DOUBLE       NOT NULL DEFAULT 0,
    course         DOUBLE       NOT NULL DEFAULT 0,
    device_time    DATETIME(6)  NOT NULL,
    server_time    DATETIME(6)  NOT NULL,
    ignition       TINYINT(1)   NULL,
    ac             TINYINT(1)   NULL,
    door           TINYINT(1)   NULL,
    lock_state     TINYINT(1)   NULL,
    battery        DOUBLE       NULL,
    external_power DOUBLE       NULL,
    gps_valid      TINYINT(1)   NOT NULL DEFAULT 1,
    satellites     INT          NULL,
    network_signal INT          NULL,
    odometer       DOUBLE       NULL,
    engine_hours   DOUBLE       NULL,
    fuel_level     DOUBLE       NULL,
    event_type     VARCHAR(48)  NULL,
    address        VARCHAR(512) NULL,
    attributes     TEXT         NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_positions_device_devicetime (device_id, device_time),
    KEY idx_positions_tenant_servertime (tenant_id, server_time),
    KEY idx_positions_event (event_type, device_time),
    CONSTRAINT fk_positions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_positions_device FOREIGN KEY (device_id) REFERENCES devices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Current position per device (fast fleet/dashboard reads).
-- Derived state stored so summaries never scan history.
-- ---------------------------------------------------------------------
CREATE TABLE device_current_position (
    device_id      BIGINT       NOT NULL,
    tenant_id      BIGINT       NOT NULL,
    vehicle_id     BIGINT       NULL,
    position_id    BIGINT       NULL,
    latitude       DOUBLE       NOT NULL,
    longitude      DOUBLE       NOT NULL,
    speed          DOUBLE       NOT NULL DEFAULT 0,
    course         DOUBLE       NOT NULL DEFAULT 0,
    ignition       TINYINT(1)   NULL,
    gps_valid      TINYINT(1)   NOT NULL DEFAULT 1,
    state          VARCHAR(16)  NOT NULL DEFAULT 'NO_DATA',
    address        VARCHAR(512) NULL,
    device_time    DATETIME(6)  NULL,
    server_time    DATETIME(6)  NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (device_id),
    KEY idx_current_tenant_state (tenant_id, state),
    CONSTRAINT fk_current_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Audit log (login, commands, expiry changes, payments, deletions...)
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NULL,
    user_id        BIGINT       NULL,
    username       VARCHAR(120) NULL,
    action         VARCHAR(64)  NOT NULL,
    entity_type    VARCHAR(64)  NULL,
    entity_id      VARCHAR(64)  NULL,
    outcome        VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    ip_address     VARCHAR(64)  NULL,
    user_agent     VARCHAR(256) NULL,
    correlation_id VARCHAR(64)  NULL,
    detail         TEXT         NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_tenant_created (tenant_id, created_at),
    KEY idx_audit_action (action),
    KEY idx_audit_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
