package com.glivt.position;

/**
 * Derived fleet state. Concrete thresholds (speed, timeouts, dwell) are
 * configurable on the backend and applied when the current position is
 * recalculated - never inferred on the mobile client.
 */
public enum DeviceState {
    RUNNING,
    STOPPED,
    IDLE,
    INACTIVE,
    NO_DATA,
    EXPIRED
}
