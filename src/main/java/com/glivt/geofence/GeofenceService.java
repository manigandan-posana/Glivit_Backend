package com.glivt.geofence;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.DeviceRepository;
import com.glivt.geofence.dto.GeofenceDto;
import com.glivt.geofence.dto.GeofenceRequest;
import com.glivt.group.DeviceGroupRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GeofenceService {

    private final GeofenceRepository repository;
    private final DeviceRepository deviceRepository;
    private final DeviceGroupRepository groupRepository;
    private final AuditService auditService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public GeofenceService(GeofenceRepository repository, DeviceRepository deviceRepository,
                           DeviceGroupRepository groupRepository, AuditService auditService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.groupRepository = groupRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<GeofenceDto> list(Long tenantId, Pageable pageable) {
        return PageResponse.from(repository.findByTenantId(tenantId, pageable), this::toDto);
    }

    @Transactional
    public GeofenceDto create(Long tenantId, Long userId, String username, GeofenceRequest request) {
        validate(tenantId, request);
        Geofence geofence = new Geofence();
        geofence.setTenantId(tenantId);
        apply(geofence, request);
        geofence = repository.save(geofence);
        auditService.record(tenantId, userId, username, "CREATE_GEOFENCE", "GEOFENCE",
                String.valueOf(geofence.getId()), "SUCCESS", null);
        return toDto(geofence);
    }

    @Transactional
    public GeofenceDto update(Long tenantId, Long userId, String username, Long id,
                              GeofenceRequest request) {
        validate(tenantId, request);
        Geofence geofence = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence not found"));
        apply(geofence, request);
        geofence = repository.save(geofence);
        auditService.record(tenantId, userId, username, "UPDATE_GEOFENCE", "GEOFENCE",
                String.valueOf(id), "SUCCESS", null);
        return toDto(geofence);
    }

    @Transactional
    public void delete(Long tenantId, Long userId, String username, Long id) {
        Geofence geofence = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Geofence not found"));
        repository.delete(geofence);
        auditService.record(tenantId, userId, username, "DELETE_GEOFENCE", "GEOFENCE",
                String.valueOf(id), "SUCCESS", null);
    }

    private void apply(Geofence geofence, GeofenceRequest request) {
        geofence.setName(request.name().trim());
        geofence.setDescription(blankToNull(request.description()));
        geofence.setColor(request.color() == null || request.color().isBlank()
                ? "#27D34D" : request.color().trim());
        geofence.setType(request.type().trim().toUpperCase());
        geofence.setCoordinatesJson(toJson(request.coordinates()));
        geofence.setRadiusMeters(request.radiusMeters());
        geofence.setCorridorWidthMeters(request.corridorWidthMeters());
        geofence.setAssignedDeviceIds(toJson(request.assignedDeviceIds()));
        geofence.setAssignedGroupIds(toJson(request.assignedGroupIds()));
        geofence.setEnterAlert(request.enterAlert() == null || request.enterAlert());
        geofence.setExitAlert(request.exitAlert() == null || request.exitAlert());
        geofence.setActiveSchedule(blankToNull(request.activeSchedule()));
        geofence.setActive(request.active() == null || request.active());
    }

    private void validate(Long tenantId, GeofenceRequest request) {
        String type = request.type().trim().toUpperCase();
        if (!List.of("CIRCLE", "POLYGON", "POLYLINE").contains(type)) {
            throw new BadRequestException("Unsupported geofence type");
        }
        if (request.coordinates().isEmpty()) {
            throw new BadRequestException("Geofence needs at least one coordinate");
        }
        if ("CIRCLE".equals(type) && (request.radiusMeters() == null || request.radiusMeters() <= 0)) {
            throw new BadRequestException("Circle geofence requires radiusMeters");
        }
        for (Long deviceId : nullToEmpty(request.assignedDeviceIds())) {
            if (deviceRepository.findByIdAndTenantId(deviceId, tenantId).isEmpty()) {
                throw new BadRequestException("Assigned device is not available for this tenant");
            }
        }
        for (Long groupId : nullToEmpty(request.assignedGroupIds())) {
            if (groupRepository.findByIdAndTenantId(groupId, tenantId).isEmpty()) {
                throw new BadRequestException("Assigned group is not available for this tenant");
            }
        }
    }

    private GeofenceDto toDto(Geofence geofence) {
        return GeofenceDto.from(geofence, fromJson(geofence.getCoordinatesJson(), new TypeReference<>() {}),
                fromJson(geofence.getAssignedDeviceIds(), new TypeReference<>() {}),
                fromJson(geofence.getAssignedGroupIds(), new TypeReference<>() {}));
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            if (json == null || json.isBlank()) {
                return jsonMapper.readValue("[]", type);
            }
            return jsonMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new BadRequestException("Stored geofence data is invalid");
        }
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ex) {
            throw new BadRequestException("Geofence data is invalid");
        }
    }

    private List<Long> nullToEmpty(List<Long> values) {
        return values == null ? List.of() : values;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
