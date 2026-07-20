package com.glivt.security;

import com.glivt.user.Role;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable view over a user's granular permission flags. Backed by the JSON
 * stored on the user row, with sensible role defaults applied so a freshly
 * created user is never left with an empty permission set.
 */
public final class Permissions {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final Set<Role> FULL_ACCESS = EnumSet.of(Role.SUPER_ADMIN);

    private final Role role;
    private final Map<String, Boolean> flags;

    private Permissions(Role role, Map<String, Boolean> flags) {
        this.role = role;
        this.flags = flags;
    }

    public static Permissions forUser(Role role, String json) {
        Map<String, Boolean> flags = new LinkedHashMap<>(defaults(role));
        if (json != null && !json.isBlank()) {
            try {
                Map<String, Boolean> stored = MAPPER.readValue(json, new TypeReference<>() {
                });
                flags.putAll(stored);
            } catch (Exception ignored) {
                // Malformed stored permissions fall back to role defaults.
            }
        }
        return new Permissions(role, flags);
    }

    /** Super Admin implicitly holds every permission. */
    public boolean has(String key) {
        if (FULL_ACCESS.contains(role)) {
            return true;
        }
        return Boolean.TRUE.equals(flags.get(key));
    }

    public Map<String, Boolean> asMap() {
        if (FULL_ACCESS.contains(role)) {
            Map<String, Boolean> all = new LinkedHashMap<>(flags);
            all.replaceAll((k, v) -> true);
            return all;
        }
        return new LinkedHashMap<>(flags);
    }

    private static Map<String, Boolean> defaults(Role role) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        switch (role) {
            case SUPER_ADMIN -> {
                // Everything - handled by FULL_ACCESS; seed the known keys as true.
                putAll(map, true);
            }
            case ADMIN -> {
                putAll(map, false);
                map.put(PermissionKeys.VIEW_ALL_VEHICLES, true);
                map.put(PermissionKeys.VIEW_LIVE_LOCATION, true);
                map.put(PermissionKeys.MANAGE_DEVICES, true);
                map.put(PermissionKeys.CREATE_DEVICE, true);
                map.put(PermissionKeys.MANAGE_USERS, true);
                map.put(PermissionKeys.MANAGE_DRIVERS, true);
                map.put(PermissionKeys.MANAGE_GROUPS, true);
                map.put(PermissionKeys.MANAGE_PROJECTS, true);
                map.put(PermissionKeys.MANAGE_GEOFENCES, true);
                map.put(PermissionKeys.SEND_COMMANDS, true);
                map.put(PermissionKeys.VIEW_REPORTS, true);
                map.put(PermissionKeys.EXPORT_REPORTS, true);
                map.put(PermissionKeys.MANAGE_REPORT_SCHEDULES, true);
                map.put(PermissionKeys.MANAGE_NOTIFICATIONS, true);
            }
            case DRIVER -> {
                putAll(map, false);
                map.put(PermissionKeys.VIEW_LIVE_LOCATION, true);
                map.put(PermissionKeys.DRIVER_DUTY, true);
            }
        }
        return map;
    }

    private static void putAll(Map<String, Boolean> map, boolean value) {
        map.put(PermissionKeys.VIEW_ALL_VEHICLES, value);
        map.put(PermissionKeys.VIEW_LIVE_LOCATION, value);
        map.put(PermissionKeys.MANAGE_DEVICES, value);
        map.put(PermissionKeys.CREATE_DEVICE, value);
        map.put(PermissionKeys.DELETE_DEVICE, value);
        map.put(PermissionKeys.RENEW_DEVICE, value);
        map.put(PermissionKeys.MANAGE_USERS, value);
        map.put(PermissionKeys.MANAGE_DRIVERS, value);
        map.put(PermissionKeys.MANAGE_GROUPS, value);
        map.put(PermissionKeys.MANAGE_PROJECTS, value);
        map.put(PermissionKeys.MANAGE_GEOFENCES, value);
        map.put(PermissionKeys.SEND_COMMANDS, value);
        map.put(PermissionKeys.VIEW_REPORTS, value);
        map.put(PermissionKeys.EXPORT_REPORTS, value);
        map.put(PermissionKeys.MANAGE_REPORT_SCHEDULES, value);
        map.put(PermissionKeys.MANAGE_NOTIFICATIONS, value);
        map.put(PermissionKeys.MANAGE_BILLING, value);
        map.put(PermissionKeys.MANAGE_EXPIRY, value);
        map.put(PermissionKeys.VIEW_POINTS, value);
        map.put(PermissionKeys.MANAGE_TENANTS, value);
        map.put(PermissionKeys.MANAGE_BRANDING, value);
        map.put(PermissionKeys.VIEW_AUDIT_LOGS, value);
        map.put(PermissionKeys.MANAGE_SERVER_SETTINGS, value);
        map.put(PermissionKeys.DRIVER_DUTY, value);
    }
}
