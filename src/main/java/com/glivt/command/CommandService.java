package com.glivt.command;

import com.glivt.audit.AuditService;
import com.glivt.command.dto.CommandDto;
import com.glivt.command.dto.CommandRequest;
import com.glivt.common.PageResponse;
import com.glivt.common.RequestContext;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.device.DeviceRepository;
import java.time.Duration;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandService {

    private static final Set<String> DESTRUCTIVE = Set.of("LOCK", "UNLOCK", "ENGINE_CUT", "ENGINE_RESTORE");

    private final CommandRepository repository;
    private final DeviceRepository deviceRepository;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;

    public CommandService(CommandRepository repository, DeviceRepository deviceRepository,
                          RateLimiter rateLimiter, AuditService auditService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CommandDto> list(Long tenantId, Pageable pageable) {
        return PageResponse.from(repository.findByTenantIdOrderByRequestedAtDesc(tenantId, pageable),
                CommandDto::from);
    }

    @Transactional
    public CommandDto submit(Long tenantId, Long userId, String username, CommandRequest request) {
        var existing = repository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey());
        if (existing.isPresent()) {
            return CommandDto.from(existing.get());
        }

        rateLimiter.check("command:" + tenantId + ":" + userId + ":" + RequestContext.getClientIp(),
                10, Duration.ofMinutes(5));
        if (deviceRepository.findByIdAndTenantId(request.deviceId(), tenantId).isEmpty()) {
            throw new BadRequestException("Device is not available for this tenant");
        }
        String commandType = request.commandType().trim().toUpperCase();
        if (DESTRUCTIVE.contains(commandType) && !Boolean.TRUE.equals(request.confirmed())) {
            throw new BadRequestException("Destructive commands require explicit confirmation");
        }

        DeviceCommand command = new DeviceCommand();
        command.setTenantId(tenantId);
        command.setDeviceId(request.deviceId());
        command.setCommandType(commandType);
        command.setPayload(blankToNull(request.payload()));
        command.setRequestedBy(userId);
        command.setIdempotencyKey(request.idempotencyKey().trim());
        command = repository.save(command);

        auditService.record(tenantId, userId, username, "SEND_COMMAND", "DEVICE",
                String.valueOf(request.deviceId()), "SUCCESS", commandType);
        return CommandDto.from(command);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
