package com.glivt.event;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.event.dto.EventCreateRequest;
import com.glivt.event.dto.EventDto;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private final EventRepository repository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;

    public EventService(EventRepository repository, DeviceRepository deviceRepository,
                        AuditService auditService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDto> list(Long tenantId, Long deviceId, String eventType,
                                       String severity, Instant fromTime, Instant toTime,
                                       Pageable pageable) {
        return PageResponse.from(repository.search(tenantId, deviceId, blankToNull(eventType),
                blankToNull(severity), fromTime, toTime, pageable), EventDto::from);
    }

    @Transactional
    public EventDto create(Long tenantId, EventCreateRequest request) {
        Device device = deviceRepository.findByIdAndTenantId(request.deviceId(), tenantId)
                .orElseThrow(() -> new BadRequestException("Device is not available for this tenant"));
        Event event = new Event();
        event.setTenantId(tenantId);
        event.setDeviceId(device.getId());
        event.setVehicleId(device.getVehicleId());
        event.setEventType(request.eventType().trim());
        event.setSeverity(request.severity() == null || request.severity().isBlank()
                ? "INFO" : request.severity().trim());
        event.setLatitude(request.latitude());
        event.setLongitude(request.longitude());
        event.setSpeed(request.speed());
        event.setAddress(blankToNull(request.address()));
        event.setDeviceTime(request.deviceTime());
        event.setServerTime(request.serverTime());
        event.setDetail(blankToNull(request.detail()));
        return EventDto.from(repository.save(event));
    }

    @Transactional
    public EventDto acknowledge(Long tenantId, Long userId, String username, Long id) {
        Event event = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
        if (!event.isAcknowledged()) {
            event.setAcknowledged(true);
            event.setAcknowledgedBy(userId);
            event.setAcknowledgedAt(Instant.now());
            event = repository.save(event);
            auditService.record(tenantId, userId, username, "ACK_EVENT", "EVENT",
                    String.valueOf(id), "SUCCESS", event.getEventType());
        }
        return EventDto.from(event);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
