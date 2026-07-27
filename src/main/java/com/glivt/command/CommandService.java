package com.glivt.command;

import com.glivt.audit.AuditService;
import com.glivt.command.dto.CommandDto;
import com.glivt.command.dto.CommandRequest;
import com.glivt.common.PageResponse;
import com.glivt.common.RequestContext;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import java.time.Duration;
import java.time.Instant;
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
        Device device = deviceRepository.findByIdAndTenantId(request.deviceId(), tenantId)
                .orElseThrow(() -> new BadRequestException("Device is not available for this tenant"));
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
        applyToDevice(device, commandType, command);
        command = repository.save(command);

        auditService.record(tenantId, userId, username, "SEND_COMMAND", "DEVICE",
                String.valueOf(request.deviceId()), "SUCCESS", commandType);
        return CommandDto.from(command);
    }

    /**
     * Applies a state-changing command to the device so the fleet immediately
     * reflects it: a cut or locked vehicle derives to {@code IMMOBILISED} with
     * zero speed instead of continuing to report its last movement.
     *
     * <p>Commands that only ask the device for something (REQUEST_LOCATION,
     * RESTART_TRACKER) change no state and stay {@code REQUESTED} until the
     * device answers.
     */
    private void applyToDevice(Device device, String commandType, DeviceCommand command) {
        switch (commandType) {
            case "ENGINE_CUT" -> device.setImmobilised(true);
            case "ENGINE_RESTORE" -> device.setImmobilised(false);
            case "LOCK" -> device.setLocked(true);
            case "UNLOCK" -> device.setLocked(false);
            default -> {
                return;
            }
        }
        device.setLastCommandType(commandType);
        device.setLastCommandAt(Instant.now());
        deviceRepository.save(device);
        command.setStatus(CommandStatus.ACKNOWLEDGED);
        command.setResponseMessage(describe(commandType));
    }

    private String describe(String commandType) {
        return switch (commandType) {
            case "ENGINE_CUT" -> "Engine cut applied; vehicle immobilised";
            case "ENGINE_RESTORE" -> "Engine restored; vehicle mobile again";
            case "LOCK" -> "Vehicle locked";
            case "UNLOCK" -> "Vehicle unlocked";
            default -> null;
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
