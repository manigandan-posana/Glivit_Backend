package com.glivt.security;

/**
 * Canonical granular permission keys. Distributor / Manager / Customer
 * behaviours from the reference product are expressed as combinations of
 * these flags rather than extra visible roles. The frontend hides menu items
 * whose permission is false; the backend enforces the same checks server-side.
 */
public final class PermissionKeys {

    private PermissionKeys() {
    }

    // Fleet / devices
    public static final String VIEW_ALL_VEHICLES = "view_all_vehicles";
    public static final String VIEW_LIVE_LOCATION = "view_live_location";
    public static final String MANAGE_DEVICES = "manage_devices";
    public static final String CREATE_DEVICE = "create_device";
    public static final String DELETE_DEVICE = "delete_device";
    public static final String RENEW_DEVICE = "renew_device";

    // Users / access
    public static final String MANAGE_USERS = "manage_users";
    public static final String MANAGE_DRIVERS = "manage_drivers";
    public static final String MANAGE_GROUPS = "manage_groups";
    public static final String MANAGE_PROJECTS = "manage_projects";
    public static final String MANAGE_GEOFENCES = "manage_geofences";

    // Operations
    public static final String SEND_COMMANDS = "send_commands";
    public static final String VIEW_REPORTS = "view_reports";
    public static final String EXPORT_REPORTS = "export_reports";
    public static final String MANAGE_REPORT_SCHEDULES = "manage_report_schedules";
    public static final String MANAGE_NOTIFICATIONS = "manage_notifications";

    // Billing
    public static final String MANAGE_BILLING = "manage_billing";
    public static final String MANAGE_EXPIRY = "manage_expiry";
    public static final String VIEW_POINTS = "view_points";

    // Platform
    public static final String MANAGE_TENANTS = "manage_tenants";
    public static final String MANAGE_BRANDING = "manage_branding";
    public static final String VIEW_AUDIT_LOGS = "view_audit_logs";
    public static final String MANAGE_SERVER_SETTINGS = "manage_server_settings";

    // Driver duty
    public static final String DRIVER_DUTY = "driver_duty";
}
