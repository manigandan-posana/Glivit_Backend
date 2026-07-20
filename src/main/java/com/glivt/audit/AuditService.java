package com.glivt.audit;

import com.glivt.common.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central audit trail. Writes in a separate transaction so an audit failure
 * never rolls back the business operation, and vice versa.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tenantId, Long userId, String username, String action,
                       String entityType, String entityId, String outcome, String detail) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setUserId(userId);
        log.setUsername(username);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setOutcome(outcome == null ? "SUCCESS" : outcome);
        log.setDetail(detail);
        log.setIpAddress(RequestContext.getClientIp());
        log.setUserAgent(RequestContext.getUserAgent());
        log.setCorrelationId(RequestContext.getCorrelationId());
        repository.save(log);
    }
}
