package com.glivt.tenant;

import com.glivt.ai.repository.AiEventRepository;
import com.glivt.command.CommandRepository;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.DriverRepository;
import com.glivt.event.EventRepository;
import com.glivt.geofence.GeofenceRepository;
import com.glivt.group.DeviceGroupRepository;
import com.glivt.position.PositionRepository;
import com.glivt.project.ProjectRepository;
import com.glivt.report.ReportRepository;
import com.glivt.user.UserRepository;
import com.glivt.vehicle.VehicleRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts the tenant-owned rows in every operational category for one tenant.
 *
 * <p>Two jobs:
 * <ul>
 *   <li>Deciding whether a tenant is safe to delete outright. A tenant holding
 *       operational data needs the explicit confirmation flow instead.</li>
 *   <li>Proving isolation in tests: immediately after creation every category of
 *       a new tenant must be zero except the one provisioned admin user.</li>
 * </ul>
 *
 * <p>Only the provisioned administrator is expected on a brand-new tenant, so
 * {@link Snapshot#isEmpty()} tolerates a single user and nothing else.
 */
@Service
public class TenantDataInventory {

    /** Counts per category plus the derived "safe to delete" verdict. */
    public record Snapshot(Map<String, Long> counts, long users, long total) {

        public boolean isEmpty() {
            return total == 0;
        }

        /** True when nothing but the provisioned admin account exists. */
        public boolean isPristine() {
            return total == 0 && users <= 1;
        }

        /** Human-readable summary of what is blocking a delete, or null when clear. */
        public String blockingSummary() {
            if (isPristine()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            counts.forEach((key, value) -> {
                if (value > 0) {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(value).append(' ').append(key);
                }
            });
            if (users > 1) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(users).append(" users");
            }
            return sb.isEmpty() ? null : sb.toString();
        }
    }

    private final DeviceRepository deviceRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final ProjectRepository projectRepository;
    private final DeviceGroupRepository groupRepository;
    private final GeofenceRepository geofenceRepository;
    private final EventRepository eventRepository;
    private final ReportRepository reportRepository;
    private final CommandRepository commandRepository;
    private final PositionRepository positionRepository;
    private final AiEventRepository aiEventRepository;
    private final UserRepository userRepository;

    public TenantDataInventory(DeviceRepository deviceRepository, VehicleRepository vehicleRepository,
                               DriverRepository driverRepository, ProjectRepository projectRepository,
                               DeviceGroupRepository groupRepository, GeofenceRepository geofenceRepository,
                               EventRepository eventRepository, ReportRepository reportRepository,
                               CommandRepository commandRepository, PositionRepository positionRepository,
                               AiEventRepository aiEventRepository, UserRepository userRepository) {
        this.deviceRepository = deviceRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.projectRepository = projectRepository;
        this.groupRepository = groupRepository;
        this.geofenceRepository = geofenceRepository;
        this.eventRepository = eventRepository;
        this.reportRepository = reportRepository;
        this.commandRepository = commandRepository;
        this.positionRepository = positionRepository;
        this.aiEventRepository = aiEventRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(Long tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("GPS devices", deviceRepository.countByTenantId(tenantId));
        counts.put("vehicles", vehicleRepository.countByTenantId(tenantId));
        counts.put("drivers", driverRepository.countByTenantId(tenantId));
        counts.put("projects", projectRepository.countByTenantId(tenantId));
        counts.put("groups", groupRepository.countByTenantId(tenantId));
        counts.put("geofences", geofenceRepository.countByTenantId(tenantId));
        counts.put("alerts", eventRepository.countByTenantId(tenantId));
        counts.put("reports", reportRepository.countByTenantId(tenantId));
        counts.put("device commands", commandRepository.countByTenantId(tenantId));
        counts.put("GPS positions", positionRepository.countByTenantId(tenantId));
        counts.put("AI events", aiEventRepository.countByTenantId(tenantId));

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new Snapshot(counts, userRepository.countByTenantId(tenantId), total);
    }
}
