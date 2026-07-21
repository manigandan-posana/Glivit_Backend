package com.glivt.access;

import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.DriverRepository;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.PermissionKeys;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central, reusable authorization for fleet entities. Enforces that a user can
 * only reach devices/vehicles/projects within both their tenant <em>and</em>
 * their assignment scope. Out-of-scope ids return 404 (not 403) so entity ids
 * cannot be enumerated.
 *
 * <p>The AI tool layer must resolve scope through the exact same policy so the
 * assistant can never widen a user's access.
 */
@Service
public class FleetAccessPolicy {

    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final VehicleDriverAssignmentRepository vehicleDriverAssignmentRepository;
    private final UserProjectAssignmentRepository userProjectAssignmentRepository;

    public FleetAccessPolicy(DeviceRepository deviceRepository,
                             DriverRepository driverRepository,
                             VehicleDriverAssignmentRepository vehicleDriverAssignmentRepository,
                             UserProjectAssignmentRepository userProjectAssignmentRepository) {
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.vehicleDriverAssignmentRepository = vehicleDriverAssignmentRepository;
        this.userProjectAssignmentRepository = userProjectAssignmentRepository;
    }

    /**
     * Resolve which devices this user may reach. Users holding
     * {@code view_all_vehicles} (admins) are unrestricted within their tenant;
     * everyone else is limited to devices on their assigned vehicles and in
     * their assigned projects.
     */
    @Transactional(readOnly = true)
    public DeviceScope deviceScope(AppUserPrincipal user) {
        if (user.hasPermission(PermissionKeys.VIEW_ALL_VEHICLES)) {
            return DeviceScope.all();
        }
        Long tenantId = user.getTenantId();
        Set<Long> ids = new HashSet<>();

        List<Long> driverIds = driverRepository.driverIdsForUser(tenantId, user.getUserId());
        if (!driverIds.isEmpty()) {
            List<Long> vehicleIds = vehicleDriverAssignmentRepository.activeVehicleIds(tenantId, driverIds);
            if (!vehicleIds.isEmpty()) {
                ids.addAll(deviceRepository.deviceIdsByVehicleIds(tenantId, vehicleIds));
            }
        }

        List<Long> projectIds = userProjectAssignmentRepository.projectIds(tenantId, user.getUserId());
        if (!projectIds.isEmpty()) {
            ids.addAll(deviceRepository.deviceIdsByProjectIds(tenantId, projectIds));
        }

        return DeviceScope.of(ids);
    }

    /** Tenant + assignment check for a single device. Throws 404 when out of scope. */
    @Transactional(readOnly = true)
    public Device requireDeviceAccess(AppUserPrincipal user, Long deviceId) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, user.getTenantId())
                .orElseThrow(FleetAccessPolicy::notFound);
        if (!deviceScope(user).allows(deviceId)) {
            throw notFound();
        }
        return device;
    }

    /** A vehicle is reachable when its tracker is reachable. */
    @Transactional(readOnly = true)
    public void requireVehicleAccess(AppUserPrincipal user, Long vehicleId) {
        if (user.hasPermission(PermissionKeys.VIEW_ALL_VEHICLES)) {
            return;
        }
        DeviceScope scope = deviceScope(user);
        List<Long> deviceIds = deviceRepository.deviceIdsByVehicleIds(user.getTenantId(), List.of(vehicleId));
        boolean allowed = deviceIds.stream().anyMatch(scope::allows);
        if (!allowed) {
            throw notFound();
        }
    }

    @Transactional(readOnly = true)
    public void requireProjectAccess(AppUserPrincipal user, Long projectId) {
        if (user.hasPermission(PermissionKeys.VIEW_ALL_VEHICLES)) {
            return;
        }
        List<Long> projectIds = userProjectAssignmentRepository.projectIds(user.getTenantId(), user.getUserId());
        if (!projectIds.contains(projectId)) {
            throw notFound();
        }
    }

    @Transactional(readOnly = true)
    public void requireDriverAccess(AppUserPrincipal user, Long driverId) {
        if (user.hasPermission(PermissionKeys.VIEW_ALL_VEHICLES)) {
            // Still enforce tenant scope.
            driverRepository.findByIdAndTenantId(driverId, user.getTenantId())
                    .orElseThrow(FleetAccessPolicy::notFound);
            return;
        }
        List<Long> own = driverRepository.driverIdsForUser(user.getTenantId(), user.getUserId());
        if (!own.contains(driverId)) {
            throw notFound();
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Not found");
    }
}
