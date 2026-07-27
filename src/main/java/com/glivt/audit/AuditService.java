package com.glivt.audit;

import com.glivt.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central audit trail. Writes in a separate transaction so an audit failure
 * never rolls back the business operation, and vice versa.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Column widths from V1__baseline_schema.sql; over-long values are trimmed, never rejected. */
    private static final int MAX_ACTION = 64;
    private static final int MAX_USERNAME = 120;
    private static final int MAX_ENTITY_TYPE = 64;
    private static final int MAX_ENTITY_ID = 64;
    private static final int MAX_OUTCOME = 16;
    private static final int MAX_IP = 64;
    private static final int MAX_USER_AGENT = 256;
    private static final int MAX_CORRELATION_ID = 64;

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes one audit row.
     *
     * The audit trail is observability, not business state: a failure here (an
     * over-long header, a locked table, a transient DB error) must never turn a
     * successful create/update into a 500 for the caller. REQUIRES_NEW isolates
     * the transaction; this try/catch isolates the exception as well.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tenantId, Long userId, String username, String action,
                       String entityType, String entityId, String outcome, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTenantId(tenantId);
            entry.setUserId(userId);
            entry.setUsername(clip(username, MAX_USERNAME));
            entry.setAction(clip(action, MAX_ACTION));
            entry.setEntityType(clip(entityType, MAX_ENTITY_TYPE));
            entry.setEntityId(clip(entityId, MAX_ENTITY_ID));
            entry.setOutcome(clip(outcome == null ? "SUCCESS" : outcome, MAX_OUTCOME));
            entry.setDetail(detail);
            entry.setIpAddress(clip(RequestContext.getClientIp(), MAX_IP));
            entry.setUserAgent(clip(RequestContext.getUserAgent(), MAX_USER_AGENT));
            entry.setCorrelationId(clip(RequestContext.getCorrelationId(), MAX_CORRELATION_ID));
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Audit write failed for action={} entity={}:{} [{}]", action, entityType,
                    entityId, RequestContext.getCorrelationId(), ex);
        }
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
