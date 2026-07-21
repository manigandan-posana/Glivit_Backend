package com.glivt.position;

/**
 * Derived fleet state, calculated on the backend from the latest telemetry and
 * the tenant's configurable thresholds - never inferred on the mobile client.
 *
 * <p>{@code RUNNING} is the "moving" state (kept under its original name for
 * backward compatibility with existing seed data, reports and clients). The
 * remaining states were added with the production telemetry pipeline.
 */
public enum DeviceState {
    /** Reporting and moving above the idle-speed threshold. */
    RUNNING,
    /** Reporting, stationary, ignition off. */
    STOPPED,
    /** Ignition on but stationary (engine idling). */
    IDLE,
    /** Legacy "not active" bucket retained for existing rows. */
    INACTIVE,
    /** No packet within the tenant offline timeout. */
    OFFLINE,
    /** Device exists but has never reported a position. */
    NO_DATA,
    /** Reporting but GPS fix is invalid. */
    GPS_INVALID,
    /** External power removed / below the configured minimum. */
    POWER_DISCONNECTED,
    /** Subscription / device expiry date has passed. */
    EXPIRED,
    /** Administratively suspended. */
    SUSPENDED
}
