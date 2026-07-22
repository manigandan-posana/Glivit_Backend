package com.glivt.device;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.DuplicateResourceException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.dto.DeviceDetail;
import com.glivt.device.dto.DeviceSummary;
import com.glivt.device.dto.DeviceUpsertRequest;
import com.glivt.group.DeviceGroupRepository;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.project.ProjectRepository;
import com.glivt.user.UserRepository;
import com.glivt.vehicle.VehicleRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final ProjectRepository projectRepository;
    private final DeviceGroupRepository groupRepository;
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public DeviceService(DeviceRepository deviceRepository,
                         DeviceCurrentPositionRepository currentPositionRepository,
                         ProjectRepository projectRepository,
                         DeviceGroupRepository groupRepository,
                         VehicleRepository vehicleRepository,
                         UserRepository userRepository,
                         AuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.projectRepository = projectRepository;
        this.groupRepository = groupRepository;
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * Paginated, tenant-scoped device list. Current positions are batch-loaded by
     * id (no N+1) and merged into each row.
     */
    @Transactional(readOnly = true)
    public PageResponse<DeviceSummary> list(Long tenantId, Long projectId, Long groupId,
                                            String search, Pageable pageable) {
        String term = (search != null && search.isBlank()) ? null : search;
        Page<Device> page = deviceRepository.search(tenantId, projectId, groupId, term, pageable);

        List<Long> ids = page.getContent().stream().map(Device::getId).toList();
        Map<Long, DeviceCurrentPosition> positions = ids.isEmpty()
                ? Map.of()
                : currentPositionRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(DeviceCurrentPosition::getDeviceId, Function.identity()));

        return PageResponse.from(page, device -> toSummary(device, positions.get(device.getId())));
    }

    @Transactional(readOnly = true)
    public DeviceDetail get(Long tenantId, Long deviceId) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
        DeviceCurrentPosition position = currentPositionRepository
                .findByDeviceIdAndTenantId(deviceId, tenantId).orElse(null);
        return toDetail(device, position);
    }

    @Transactional
    public DeviceDetail create(Long tenantId, Long userId, String username, DeviceUpsertRequest request) {
        if (deviceRepository.existsByImeiIgnoreCase(request.imei())) {
            throw new DuplicateResourceException("IMEI already exists");
        }
        validateReferences(tenantId, request, null);

        Device device = new Device();
        apply(device, tenantId, request);
        device = deviceRepository.save(device);
        auditService.record(tenantId, userId, username, "CREATE_DEVICE", "DEVICE",
                String.valueOf(device.getId()), "SUCCESS", null);
        return toDetail(device, null);
    }

    @Transactional
    public DeviceDetail update(Long tenantId, Long userId, String username, Long deviceId,
                               DeviceUpsertRequest request) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
        if (deviceRepository.existsByImeiIgnoreCaseAndIdNot(request.imei(), deviceId)) {
            throw new DuplicateResourceException("IMEI already exists");
        }
        validateReferences(tenantId, request, deviceId);
        apply(device, tenantId, request);
        device = deviceRepository.save(device);
        DeviceCurrentPosition position = currentPositionRepository
                .findByDeviceIdAndTenantId(deviceId, tenantId).orElse(null);
        auditService.record(tenantId, userId, username, "UPDATE_DEVICE", "DEVICE",
                String.valueOf(device.getId()), "SUCCESS", null);
        return toDetail(device, position);
    }

    /**
     * Issues a fresh opaque ingestion token for the device (tenant-scoped) and
     * returns it once. Rotating invalidates any previous token.
     */
    @Transactional
    public String rotateIngestToken(Long tenantId, Long userId, String username, Long deviceId) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
        String token = generateIngestToken();
        device.setIngestToken(token);
        deviceRepository.save(device);
        auditService.record(tenantId, userId, username, "ROTATE_INGEST_TOKEN", "DEVICE",
                String.valueOf(deviceId), "SUCCESS", null);
        return token;
    }

    private static String generateIngestToken() {
        byte[] bytes = new byte[36];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public void suspend(Long tenantId, Long userId, String username, Long deviceId) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
        device.setStatus(DeviceStatus.SUSPENDED);
        device.setVehicleId(null);
        deviceRepository.save(device);
        auditService.record(tenantId, userId, username, "DELETE_DEVICE", "DEVICE",
                String.valueOf(device.getId()), "SUCCESS", "Soft deleted");
    }

    private DeviceSummary toSummary(Device d, DeviceCurrentPosition p) {
        return new DeviceSummary(
                d.getId(), d.getName(), d.getImei(), d.getCategory(), d.getVehicleId(),
                stateOf(d, p),
                p != null ? p.getLatitude() : null,
                p != null ? p.getLongitude() : null,
                p != null ? p.getSpeed() : 0,
                p != null ? p.getCourse() : 0,
                p != null ? p.getIgnition() : null,
                p != null && p.isGpsValid(),
                p != null ? p.getAddress() : null,
                p != null ? p.getServerTime() : null,
                d.getExpiryDate(),
                d.getStatus().name());
    }

    private DeviceDetail toDetail(Device d, DeviceCurrentPosition p) {
        return new DeviceDetail(
                d.getId(), d.getName(), d.getImei(), d.getModel(), d.getCategory(),
                d.getProjectId(), d.getGroupId(), d.getVehicleId(), d.getManagerId(),
                d.getSimNumber(), d.getSimProvider(), d.getSimApn(),
                d.getDriverName(), d.getDriverPhone(), d.getAddress(), d.getRemarks(),
                d.getExpiryDate(), d.getActivatedAt(), d.getTimezone(),
                d.getDistanceUnit(), d.getSpeedUnit(), d.getStatus().name(),
                stateOf(d, p),
                p != null ? p.getLatitude() : null,
                p != null ? p.getLongitude() : null,
                p != null ? p.getSpeed() : 0,
                p != null ? p.getCourse() : 0,
                p != null ? p.getIgnition() : null,
                p != null && p.isGpsValid(),
                p != null ? p.getServerTime() : null);
    }

    private void apply(Device device, Long tenantId, DeviceUpsertRequest r) {
        device.setTenantId(tenantId);
        device.setName(r.name().trim());
        device.setImei(r.imei().trim());
        device.setSimNumber(blankToNull(r.simNumber()));
        device.setModel(blankToNull(r.model()));
        device.setPort(r.port());
        device.setCategory(blankToDefault(r.category(), "GPS"));
        device.setProjectId(r.projectId());
        device.setGroupId(r.groupId());
        device.setVehicleId(r.vehicleId());
        device.setManagerId(r.managerId());
        device.setDriverName(blankToNull(r.driverName()));
        device.setDriverPhone(blankToNull(r.driverPhone()));
        device.setRemarks(blankToNull(r.remarks()));
        device.setAddress(blankToNull(r.address()));
        device.setSimProvider(blankToNull(r.simProvider()));
        device.setSimApn(blankToNull(r.simApn()));
        device.setExpiryDate(r.expiryDate());
        device.setActivatedAt(r.activatedAt());
        device.setTimezone(blankToDefault(r.timezone(), "Asia/Kolkata"));
        device.setDistanceUnit(blankToDefault(r.distanceUnit(), "KM"));
        device.setSpeedUnit(blankToDefault(r.speedUnit(), "KMH"));
        if (device.getStatus() == null) {
            device.setStatus(DeviceStatus.ACTIVE);
        }
    }

    private void validateReferences(Long tenantId, DeviceUpsertRequest r, Long currentDeviceId) {
        if (r.activatedAt() != null && r.expiryDate() != null
                && r.expiryDate().isBefore(r.activatedAt())) {
            throw new BadRequestException("Expiry cannot be before activation date");
        }
        if (r.projectId() != null && projectRepository.findByIdAndTenantId(r.projectId(), tenantId).isEmpty()) {
            throw new BadRequestException("Project is not available for this tenant");
        }
        if (r.groupId() != null && groupRepository.findByIdAndTenantId(r.groupId(), tenantId).isEmpty()) {
            throw new BadRequestException("Group is not available for this tenant");
        }
        if (r.managerId() != null && userRepository.findByIdAndTenantId(r.managerId(), tenantId).isEmpty()) {
            throw new BadRequestException("Manager is not available for this tenant");
        }
        if (r.vehicleId() != null) {
            if (vehicleRepository.findByIdAndTenantId(r.vehicleId(), tenantId).isEmpty()) {
                throw new BadRequestException("Vehicle is not available for this tenant");
            }
            boolean assigned = currentDeviceId == null
                    ? deviceRepository.existsByVehicleIdAndTenantId(r.vehicleId(), tenantId)
                    : deviceRepository.existsByVehicleIdAndTenantIdAndIdNot(
                            r.vehicleId(), tenantId, currentDeviceId);
            if (assigned) {
                throw new DuplicateResourceException("Vehicle already has an active tracker");
            }
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String stateOf(Device d, DeviceCurrentPosition p) {
        if (d.getStatus() == DeviceStatus.EXPIRED) {
            return DeviceState.EXPIRED.name();
        }
        return p != null ? p.getState().name() : DeviceState.NO_DATA.name();
    }
}
