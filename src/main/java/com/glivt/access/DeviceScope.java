package com.glivt.access;

import java.util.Set;

/**
 * The set of devices a user may reach.
 *
 * <p>{@code unrestricted} means "every device in the tenant" (admins, or any
 * user holding {@code view_all_vehicles}). Otherwise {@code deviceIds} is the
 * explicit allow-list resolved from driver and project assignments - an empty
 * set means the user can reach nothing.
 */
public record DeviceScope(boolean unrestricted, Set<Long> deviceIds) {

    public static DeviceScope all() {
        return new DeviceScope(true, Set.of());
    }

    public static DeviceScope of(Set<Long> deviceIds) {
        return new DeviceScope(false, deviceIds);
    }

    public boolean allows(Long deviceId) {
        return unrestricted || deviceIds.contains(deviceId);
    }
}
