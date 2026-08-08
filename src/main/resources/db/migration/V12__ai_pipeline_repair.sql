-- =====================================================================
-- Glivt GPS platform - AI pipeline repair (V12)
-- MySQL 8 / InnoDB / utf8mb4. Multi-tenant; every table carries tenant_id.
--
-- Adds the data the AI pipeline was previously missing or faking:
--   * planned routes + assignments        -> real route-deviation distance
--   * persistent per-device motion state  -> continuous stationary duration
--   * server-side speed policy            -> device speed limits are not trusted
--   * AI incident columns                 -> deduplication, lifecycle, escalation
--   * transactional outbox                -> bounded async AI evaluation
--   * semantic index                      -> real embeddings over tenant records
--   * AI operation log                    -> model / prompt / rule governance
--   * scheduled job lock                  -> safe multi-instance scheduling
-- =====================================================================

-- ---------------------------------------------------------------------
-- Planned routes
-- ---------------------------------------------------------------------
CREATE TABLE vehicle_route (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id                BIGINT       NOT NULL,
    name                     VARCHAR(160) NOT NULL,
    description              VARCHAR(512) NULL,
    -- Ordered polyline as JSON: [[lat,lng],[lat,lng],...]
    path_json                LONGTEXT     NOT NULL,
    allowed_deviation_meters DOUBLE       NOT NULL DEFAULT 500.0,
    speed_limit_kph          DOUBLE       NULL,
    active                   TINYINT(1)   NOT NULL DEFAULT 1,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_vehicle_route_tenant (tenant_id, active),
    CONSTRAINT fk_vehicle_route_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vehicle_route_assignment (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  BIGINT      NOT NULL,
    route_id   BIGINT      NOT NULL,
    vehicle_id BIGINT      NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time   DATETIME(6) NULL,
    active     TINYINT(1)  NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_route_assign_lookup (tenant_id, vehicle_id, active, start_time),
    CONSTRAINT fk_route_assign_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id),
    CONSTRAINT fk_route_assign_route   FOREIGN KEY (route_id)   REFERENCES vehicle_route (id) ON DELETE CASCADE,
    CONSTRAINT fk_route_assign_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Persistent per-device motion state.
-- Continuous stationary time must survive across packets and restarts; it was
-- previously derived from a single packet gap, which could never exceed the
-- reporting interval and so never reached the idling threshold.
-- ---------------------------------------------------------------------
CREATE TABLE device_motion_state (
    device_id                     BIGINT      NOT NULL,
    tenant_id                     BIGINT      NOT NULL,
    vehicle_id                    BIGINT      NULL,
    stationary_since              DATETIME(6) NULL,
    last_movement_at              DATETIME(6) NULL,
    continuous_stationary_seconds DOUBLE      NOT NULL DEFAULT 0.0,
    ignition_on                   TINYINT(1)  NULL,
    previously_moving             TINYINT(1)  NOT NULL DEFAULT 0,
    last_device_time              DATETIME(6) NULL,
    last_latitude                 DOUBLE      NULL,
    last_longitude                DOUBLE      NULL,
    out_of_order_packets          BIGINT      NOT NULL DEFAULT 0,
    duplicate_packets             BIGINT      NOT NULL DEFAULT 0,
    low_confidence_packets        BIGINT      NOT NULL DEFAULT 0,
    updated_at                    DATETIME(6) NOT NULL,
    PRIMARY KEY (device_id),
    KEY idx_motion_state_tenant (tenant_id),
    CONSTRAINT fk_motion_state_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_motion_state_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Server-side speed policy. A speed limit reported by a GPS device is never
-- trusted; it is resolved here in priority order:
--   route rule > geofence rule > road metadata > tenant policy > type default
-- A NULL vehicle_category row is the tenant-wide default.
-- ---------------------------------------------------------------------
CREATE TABLE tenant_speed_policy (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT      NOT NULL,
    vehicle_category VARCHAR(32) NULL,
    speed_limit_kph  DOUBLE      NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_speed_policy (tenant_id, vehicle_category),
    CONSTRAINT fk_speed_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Optional per-geofence speed rule (for example a depot or school zone).
ALTER TABLE geofences ADD COLUMN speed_limit_kph DOUBLE NULL;

-- ---------------------------------------------------------------------
-- AI event -> AI incident.
-- Continuous speeding must update ONE active incident instead of inserting a
-- row per GPS packet, so the event table gains a deterministic fingerprint and
-- a lifecycle.
-- ---------------------------------------------------------------------
ALTER TABLE ai_event
    ADD COLUMN fingerprint          VARCHAR(96)  NULL,
    ADD COLUMN status               VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    ADD COLUMN occurrence_count     INT          NOT NULL DEFAULT 1,
    ADD COLUMN first_observed_at    DATETIME(6)  NULL,
    ADD COLUMN last_observed_at     DATETIME(6)  NULL,
    ADD COLUMN max_score            DOUBLE       NOT NULL DEFAULT 0.0,
    ADD COLUMN related_event_types  VARCHAR(512) NULL,
    ADD COLUMN route_id             BIGINT       NULL,
    ADD COLUMN assignment_id        BIGINT       NULL,
    ADD COLUMN distance_from_route_meters DOUBLE NULL,
    ADD COLUMN speed_limit_kph      DOUBLE       NULL,
    ADD COLUMN speed_limit_source   VARCHAR(32)  NULL,
    ADD COLUMN resolved_at          DATETIME(6)  NULL,
    ADD COLUMN resolved_by          BIGINT       NULL,
    ADD COLUMN source               VARCHAR(32)  NOT NULL DEFAULT 'RULE',
    ADD COLUMN model_name           VARCHAR(96)  NULL,
    ADD COLUMN model_version        VARCHAR(64)  NULL,
    ADD COLUMN rule_version         VARCHAR(64)  NULL,
    ADD COLUMN prompt_version       VARCHAR(64)  NULL,
    ADD COLUMN processing_ms        BIGINT       NULL,
    ADD COLUMN fallback_reason      VARCHAR(64)  NULL,
    ADD COLUMN updated_at           DATETIME(6)  NULL;

-- One OPEN incident per (tenant, fingerprint). Repeat packets update it.
CREATE UNIQUE INDEX uk_ai_event_open_fingerprint
    ON ai_event (tenant_id, fingerprint, status);
CREATE INDEX idx_ai_event_status ON ai_event (tenant_id, status, last_observed_at);

UPDATE ai_event SET first_observed_at = created_at, last_observed_at = created_at,
                    max_score = score, updated_at = created_at
 WHERE first_observed_at IS NULL;

-- ---------------------------------------------------------------------
-- Transactional outbox for AI evaluation.
-- GPS ingestion commits a row here and returns; a bounded poller performs the
-- AI work. Ingestion therefore never waits on Python or Ollama, and nothing is
-- lost if the AI worker restarts.
-- ---------------------------------------------------------------------
CREATE TABLE ai_position_outbox (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT      NOT NULL,
    device_id     BIGINT      NOT NULL,
    vehicle_id    BIGINT      NULL,
    position_id   BIGINT      NULL,
    payload_json  LONGTEXT    NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    VARCHAR(255) NULL,
    recorded_at   DATETIME(6) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    processed_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- Coalescing: at most one PENDING row per device; the newest valid point wins.
    UNIQUE KEY uk_outbox_pending_device (device_id, status),
    KEY idx_outbox_status (status, created_at),
    KEY idx_outbox_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Semantic index over real tenant-owned records.
-- Tenant filtering happens here, before anything is ranked, so a search can
-- never reach another tenant's rows.
-- ---------------------------------------------------------------------
CREATE TABLE ai_semantic_index (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL,
    source_type    VARCHAR(32)  NOT NULL,
    source_id      BIGINT       NOT NULL,
    vehicle_id     BIGINT       NULL,
    driver_id      BIGINT       NULL,
    content        TEXT         NOT NULL,
    metadata_json  TEXT         NULL,
    embedding_json LONGTEXT     NULL,
    embedding_model VARCHAR(96) NULL,
    embedding_dim  INT          NULL,
    occurred_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_semantic_source (tenant_id, source_type, source_id),
    KEY idx_semantic_tenant (tenant_id, occurred_at),
    KEY idx_semantic_vehicle (tenant_id, vehicle_id),
    CONSTRAINT fk_semantic_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Model / prompt / rule governance. Every AI result records what produced it.
-- ---------------------------------------------------------------------
CREATE TABLE ai_operation_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT      NULL,
    operation       VARCHAR(64) NOT NULL,
    source          VARCHAR(32) NOT NULL,
    model_name      VARCHAR(96) NULL,
    model_version   VARCHAR(64) NULL,
    prompt_version  VARCHAR(64) NULL,
    rule_version    VARCHAR(64) NULL,
    processing_ms   BIGINT      NOT NULL DEFAULT 0,
    fallback_reason VARCHAR(64) NULL,
    reference_type  VARCHAR(32) NULL,
    reference_id    BIGINT      NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_op_log_tenant (tenant_id, operation, created_at),
    KEY idx_ai_op_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Distributed scheduling lock so a job runs once across all backend instances.
-- ---------------------------------------------------------------------
CREATE TABLE scheduled_job_lock (
    job_name    VARCHAR(96)  NOT NULL,
    locked_by   VARCHAR(128) NOT NULL,
    locked_at   DATETIME(6)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (job_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- Trip feature snapshot: the extra features the driver score needs.
-- ---------------------------------------------------------------------
ALTER TABLE trip_feature_snapshot
    ADD COLUMN speeding_seconds        INT    NOT NULL DEFAULT 0,
    ADD COLUMN speeding_event_count    INT    NOT NULL DEFAULT 0,
    ADD COLUMN harsh_accel_count       INT    NOT NULL DEFAULT 0,
    ADD COLUMN harsh_brake_count       INT    NOT NULL DEFAULT 0,
    ADD COLUMN sharp_turn_count        INT    NOT NULL DEFAULT 0,
    ADD COLUMN night_driving_minutes   INT    NOT NULL DEFAULT 0,
    ADD COLUMN route_deviation_count   INT    NOT NULL DEFAULT 0,
    ADD COLUMN critical_incident_count INT    NOT NULL DEFAULT 0,
    ADD COLUMN high_incident_count     INT    NOT NULL DEFAULT 0,
    ADD COLUMN avg_gps_confidence      DOUBLE NOT NULL DEFAULT 1.0,
    ADD COLUMN min_gps_confidence      DOUBLE NOT NULL DEFAULT 1.0,
    ADD COLUMN status                  VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';

CREATE INDEX idx_trip_feature_driver ON trip_feature_snapshot (tenant_id, driver_id, start_time);

-- ---------------------------------------------------------------------
-- Driver score provenance and geofence suggestion visit statistics.
-- ---------------------------------------------------------------------
ALTER TABLE driver_score_daily
    ADD COLUMN risk_level     VARCHAR(16) NOT NULL DEFAULT 'LOW',
    ADD COLUMN grade          VARCHAR(4)  NULL,
    ADD COLUMN reasons_json   TEXT        NULL,
    ADD COLUMN source         VARCHAR(32) NOT NULL DEFAULT 'RULE',
    ADD COLUMN model_version  VARCHAR(64) NULL,
    ADD COLUMN rule_version   VARCHAR(64) NULL,
    ADD COLUMN calculated_at  DATETIME(6) NULL;

ALTER TABLE geofence_suggestion
    ADD COLUMN visit_count           INT         NOT NULL DEFAULT 0,
    ADD COLUMN average_stop_minutes  DOUBLE      NOT NULL DEFAULT 0.0,
    ADD COLUMN first_visit_at        DATETIME(6) NULL,
    ADD COLUMN last_visit_at         DATETIME(6) NULL,
    ADD COLUMN distinct_vehicle_count INT        NOT NULL DEFAULT 0,
    ADD COLUMN dismissed_by          BIGINT      NULL,
    ADD COLUMN updated_at            DATETIME(6) NULL;

ALTER TABLE maintenance_prediction
    ADD COLUMN predicted_component VARCHAR(64) NULL,
    ADD COLUMN components_json     TEXT        NULL,
    ADD COLUMN remaining_km        DOUBLE      NULL,
    ADD COLUMN confidence          DOUBLE      NOT NULL DEFAULT 0.6,
    ADD COLUMN source              VARCHAR(32) NOT NULL DEFAULT 'RULE',
    ADD COLUMN model_version       VARCHAR(64) NULL,
    ADD COLUMN rule_version        VARCHAR(64) NULL,
    ADD COLUMN evaluated_at        DATETIME(6) NULL;
