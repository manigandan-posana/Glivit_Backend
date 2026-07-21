package com.glivt.audit.dto;

import com.glivt.audit.AuditLog;
import java.time.Instant;

public record AuditDto(
        Long id,
        Long userId,
        String username,
        String action,
        String entityType,
        String entityId,
        String outcome,
        String correlationId,
        String detail,
        Instant createdAt) {

    public static AuditDto from(AuditLog log) {
        return new AuditDto(log.getId(), log.getUserId(), log.getUsername(), log.getAction(),
                log.getEntityType(), log.getEntityId(), log.getOutcome(),
                log.getCorrelationId(), log.getDetail(), log.getCreatedAt());
    }
}
