package com.glivt.ai.security;

import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.Driver;
import com.glivt.driver.DriverRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import org.springframework.stereotype.Component;

/**
 * Central authorization gate for AI/tracking reads. Every AI query that names a
 * vehicle, device or driver must pass through here first so a caller can never
 * reach another tenant's resource by guessing an id (IDOR). Resolution is always
 * scoped by the tenant taken from the authenticated principal; a miss is reported
 * as "not found" rather than "forbidden" so ids cannot be enumerated across
 * tenants.
 */
@Component
public class FleetAccessPolicy {

    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;

    public FleetAccessPolicy(VehicleRepository vehicleRepository,
                             DeviceRepository deviceRepository,
                             DriverRepository driverRepository) {
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
    }

    public Vehicle requireVehicle(Long tenantId, Long vehicleId) {
        if (vehicleId == null) {
            throw new ResourceNotFoundException("Vehicle not found");
        }
        return vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }

    public Device requireDevice(Long tenantId, Long deviceId) {
        if (deviceId == null) {
            throw new ResourceNotFoundException("Device not found");
        }
        return deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
    }

    public Driver requireDriver(Long tenantId, Long driverId) {
        if (driverId == null) {
            throw new ResourceNotFoundException("Driver not found");
        }
        return driverRepository.findByIdAndTenantId(driverId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
    }
}
