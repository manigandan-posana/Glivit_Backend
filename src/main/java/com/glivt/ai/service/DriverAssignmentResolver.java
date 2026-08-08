package com.glivt.ai.service;

import com.glivt.access.VehicleDriverAssignment;
import com.glivt.access.VehicleDriverAssignmentRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves which driver was responsible for a vehicle at a given moment.
 *
 * <p>AI events previously stored a null driver, which made driver scoring and
 * accountability impossible. The assignment active at the GPS timestamp is used
 * (not "now"), so a late-arriving packet is attributed to whoever was actually
 * driving. Every lookup is tenant-scoped.
 */
@Service
public class DriverAssignmentResolver {

    private final VehicleDriverAssignmentRepository assignmentRepository;
    private final VehicleRepository vehicleRepository;

    public DriverAssignmentResolver(VehicleDriverAssignmentRepository assignmentRepository,
            VehicleRepository vehicleRepository) {
        this.assignmentRepository = assignmentRepository;
        this.vehicleRepository = vehicleRepository;
    }

    public record ResolvedDriver(Long driverId, Long assignmentId, String source) {
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedDriver> resolve(Long tenantId, Long vehicleId, Instant at) {
        if (tenantId == null || vehicleId == null) {
            return Optional.empty();
        }
        Instant when = at != null ? at : Instant.now();

        Optional<ResolvedDriver> fromAssignment = assignmentRepository
                .findByTenantIdAndVehicleIdAndActiveTrue(tenantId, vehicleId).stream()
                .filter(a -> coversInstant(a, when))
                .max(Comparator.comparing(VehicleDriverAssignment::getStartTime))
                .map(a -> new ResolvedDriver(a.getDriverId(), a.getId(), "ASSIGNMENT"));
        if (fromAssignment.isPresent()) {
            return fromAssignment;
        }

        // Fall back to the vehicle's default driver, still tenant-scoped.
        return vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .map(Vehicle::getDriverId)
                .filter(java.util.Objects::nonNull)
                .map(driverId -> new ResolvedDriver(driverId, null, "VEHICLE_DEFAULT"));
    }

    private static boolean coversInstant(VehicleDriverAssignment assignment, Instant when) {
        Instant start = assignment.getStartTime();
        Instant end = assignment.getEndTime();
        if (start != null && start.isAfter(when)) {
            return false;
        }
        return end == null || !end.isBefore(when);
    }
}
