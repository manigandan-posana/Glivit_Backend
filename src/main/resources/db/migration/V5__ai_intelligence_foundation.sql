-- =====================================================================
-- Glivt GPS platform - AI Intelligence Foundation (V5)
-- MySQL 8 / InnoDB / utf8mb4. Multi-tenant.
-- Provides tables for AI anomalies, feedback, driver scores, trip features,
-- predictive maintenance, geofence clustering, dispatch ranking,
-- model registry, and prompt versioning.
-- =====================================================================

CREATE TABLE ai_event (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    vehicle_id          BIGINT       NULL,
    device_id           BIGINT       NULL,
    driver_id           BIGINT       NULL,
    event_type          VARCHAR(48)  NOT NULL,
    severity            VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    score               DOUBLE       NOT NULL DEFAULT 0.0,
    latitude            DOUBLE       NULL,
    longitude           DOUBLE       NULL,
    speed               DOUBLE       NULL,
    deviation_path_json TEXT         NULL,
    reentry_point_json  TEXT         NULL,
    explanation         TEXT         NULL,
    evidence_json       TEXT         NULL,
    acknowledged        TINYINT(1)   NOT NULL DEFAULT 0,
    acknowledged_by     BIGINT       NULL,
    acknowledged_at     DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_event_tenant_created (tenant_id, created_at),
    KEY idx_ai_event_vehicle_created (vehicle_id, created_at),
    KEY idx_ai_event_type_severity (tenant_id, event_type, severity),
    CONSTRAINT fk_ai_event_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_ai_event_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT fk_ai_event_device  FOREIGN KEY (device_id)  REFERENCES devices (id),
    CONSTRAINT fk_ai_event_driver  FOREIGN KEY (driver_id)  REFERENCES drivers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_feedback (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    ai_event_id   BIGINT      NULL,
    feature_type  VARCHAR(48) NOT NULL,
    user_id       BIGINT      NOT NULL,
    is_correct    TINYINT(1)  NOT NULL,
    feedback_type VARCHAR(32) NOT NULL DEFAULT 'AGREE',
    comments      TEXT        NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_feedback_tenant (tenant_id),
    KEY idx_ai_feedback_event (ai_event_id),
    CONSTRAINT fk_ai_feedback_tenant FOREIGN KEY (tenant_id)   REFERENCES tenants (id),
    CONSTRAINT fk_ai_feedback_event  FOREIGN KEY (ai_event_id) REFERENCES ai_event (id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_feedback_user   FOREIGN KEY (user_id)     REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE driver_score_daily (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id              BIGINT      NOT NULL,
    driver_id              BIGINT      NOT NULL,
    vehicle_id             BIGINT      NULL,
    score_date             DATE        NOT NULL,
    score_period           VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    safety_score           DOUBLE      NOT NULL DEFAULT 100.0,
    efficiency_score       DOUBLE      NOT NULL DEFAULT 100.0,
    compliance_score       DOUBLE      NOT NULL DEFAULT 100.0,
    overall_score          DOUBLE      NOT NULL DEFAULT 100.0,
    total_distance_km      DOUBLE      NOT NULL DEFAULT 0.0,
    total_driving_minutes  INT         NOT NULL DEFAULT 0,
    harsh_accel_count      INT         NOT NULL DEFAULT 0,
    harsh_brake_count      INT         NOT NULL DEFAULT 0,
    sharp_turn_count       INT         NOT NULL DEFAULT 0,
    speeding_seconds       INT         NOT NULL DEFAULT 0,
    excessive_idle_minutes INT         NOT NULL DEFAULT 0,
    anomalies_count        INT         NOT NULL DEFAULT 0,
    breakdown_json         TEXT        NULL,
    created_at             DATETIME(6) NOT NULL,
    updated_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_score_period (tenant_id, driver_id, score_date, score_period),
    KEY idx_driver_score_tenant_date (tenant_id, score_date),
    CONSTRAINT fk_driver_score_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_driver_score_driver  FOREIGN KEY (driver_id)  REFERENCES drivers (id),
    CONSTRAINT fk_driver_score_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trip_feature_snapshot (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT       NOT NULL,
    trip_id              BIGINT       NULL,
    vehicle_id           BIGINT       NOT NULL,
    driver_id            BIGINT       NULL,
    start_time           DATETIME(6)  NOT NULL,
    end_time             DATETIME(6)  NULL,
    distance_km          DOUBLE       NOT NULL DEFAULT 0.0,
    duration_minutes     INT          NOT NULL DEFAULT 0,
    idle_duration_minutes INT         NOT NULL DEFAULT 0,
    avg_speed_kph        DOUBLE       NOT NULL DEFAULT 0.0,
    max_speed_kph        DOUBLE       NOT NULL DEFAULT 0.0,
    stop_count           INT          NOT NULL DEFAULT 0,
    abnormal_event_count INT          NOT NULL DEFAULT 0,
    harsh_event_count    INT          NOT NULL DEFAULT 0,
    delay_minutes        INT          NOT NULL DEFAULT 0,
    stops_json           TEXT         NULL,
    summary_text         TEXT         NULL,
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trip_feature_tenant_time (tenant_id, start_time),
    KEY idx_trip_feature_vehicle (vehicle_id, start_time),
    CONSTRAINT fk_trip_feature_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_trip_feature_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT fk_trip_feature_driver  FOREIGN KEY (driver_id)  REFERENCES drivers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintenance_prediction (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id                  BIGINT       NOT NULL,
    vehicle_id                 BIGINT       NOT NULL,
    risk_score                 DOUBLE       NOT NULL DEFAULT 0.0,
    risk_level                 VARCHAR(16)  NOT NULL DEFAULT 'LOW',
    predicted_failure_date     DATE         NULL,
    predicted_days_remaining   INT          NULL,
    odometer_at_prediction     DOUBLE       NOT NULL DEFAULT 0.0,
    engine_hours_at_prediction DOUBLE       NOT NULL DEFAULT 0.0,
    battery_health             DOUBLE       NOT NULL DEFAULT 100.0,
    driving_stress_factor      DOUBLE       NOT NULL DEFAULT 1.0,
    recommended_action         TEXT         NULL,
    reasoning                  TEXT         NULL,
    status                     VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_maint_pred_tenant_status (tenant_id, status),
    KEY idx_maint_pred_vehicle (vehicle_id, created_at),
    CONSTRAINT fk_maint_pred_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_maint_pred_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE geofence_suggestion (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id               BIGINT       NOT NULL,
    suggested_name          VARCHAR(160) NOT NULL,
    center_latitude         DOUBLE       NOT NULL,
    center_longitude        DOUBLE       NOT NULL,
    suggested_radius_meters DOUBLE       NOT NULL DEFAULT 200.0,
    cluster_point_count     INT          NOT NULL DEFAULT 0,
    confidence              DOUBLE       NOT NULL DEFAULT 0.0,
    reasoning               TEXT         NULL,
    polygon_json            TEXT         NULL,
    status                  VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    created_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_geofence_sug_tenant_status (tenant_id, status),
    CONSTRAINT fk_geofence_sug_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dispatch_recommendation (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id              BIGINT      NOT NULL,
    job_description        TEXT        NOT NULL,
    origin_lat             DOUBLE      NOT NULL,
    origin_lng             DOUBLE      NOT NULL,
    destination_lat        DOUBLE      NOT NULL,
    destination_lng        DOUBLE      NOT NULL,
    ranked_vehicles_json   TEXT        NOT NULL,
    selected_vehicle_id    BIGINT      NULL,
    recommendation_reason  TEXT        NULL,
    created_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dispatch_rec_tenant (tenant_id, created_at),
    CONSTRAINT fk_dispatch_rec_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_model_registry (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NULL,
    model_name      VARCHAR(96)  NOT NULL,
    model_version   VARCHAR(32)  NOT NULL,
    model_type      VARCHAR(48)  NOT NULL,
    file_path       VARCHAR(256) NULL,
    parameters_json TEXT         NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    metrics_json    TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_model_name_ver (model_name, model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_prompt_version (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    prompt_key    VARCHAR(96)  NOT NULL,
    version       VARCHAR(32)  NOT NULL,
    template_text TEXT         NOT NULL,
    system_prompt TEXT         NULL,
    model_name    VARCHAR(96)  NOT NULL DEFAULT 'qwen3.5:2b',
    active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_prompt_key_ver (prompt_key, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
