package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.ai.entity.AiPositionOutbox;
import com.glivt.ai.repository.AiPositionOutboxRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, bounded queue between GPS ingestion and AI evaluation.
 *
 * <p>Ingestion enqueues and returns; it never waits on Python or Ollama. Because
 * the queue is a table, a backend restart does not lose pending evaluations, and
 * because there is at most one PENDING row per device, a burst of positions
 * coalesces to the newest valid point instead of piling up.
 */
@Service
public class AiPositionOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AiPositionOutboxService.class);
    private static final int MAX_ATTEMPTS = 3;

    /** Positions coalesced away because a newer one arrived first. */
    public static final AtomicLong COALESCED = new AtomicLong();
    /** Evaluations abandoned after exhausting retries. */
    public static final AtomicLong DROPPED = new AtomicLong();

    private final AiPositionOutboxRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    public AiPositionOutboxService(AiPositionOutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * Enqueue a position for AI evaluation.
     *
     * <p>Joins the caller's transaction on purpose: the outbox row must commit
     * atomically with the position, so an evaluation is never queued for a
     * position that was rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(AiEvaluationPayload payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Could not serialise AI evaluation payload for device {}: {}",
                    payload.deviceId(), ex.getMessage());
            return;
        }

        Optional<AiPositionOutbox> pending =
                repository.findByDeviceIdAndStatus(payload.deviceId(), AiPositionOutbox.STATUS_PENDING);

        if (pending.isPresent()) {
            AiPositionOutbox entry = pending.get();
            // Keep the newest valid point; an older packet must not overwrite it.
            if (entry.getRecordedAt() != null && payload.recordedAt() != null
                    && entry.getRecordedAt().isAfter(payload.recordedAt())) {
                COALESCED.incrementAndGet();
                return;
            }
            entry.setPayloadJson(json);
            entry.setPositionId(payload.positionId());
            entry.setVehicleId(payload.vehicleId());
            entry.setRecordedAt(payload.recordedAt());
            repository.save(entry);
            COALESCED.incrementAndGet();
            return;
        }

        AiPositionOutbox entry = new AiPositionOutbox();
        entry.setTenantId(payload.tenantId());
        entry.setDeviceId(payload.deviceId());
        entry.setVehicleId(payload.vehicleId());
        entry.setPositionId(payload.positionId());
        entry.setPayloadJson(json);
        entry.setRecordedAt(payload.recordedAt() != null ? payload.recordedAt() : Instant.now());
        try {
            repository.save(entry);
        } catch (DataIntegrityViolationException race) {
            // Another thread enqueued for this device first; its row is newer or
            // equivalent, so dropping this one is correct.
            COALESCED.incrementAndGet();
        }
    }

    /** Claims an entry for processing, or returns empty if another worker won. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AiPositionOutbox> claim(Long id) {
        return repository.findById(id)
                .filter(entry -> AiPositionOutbox.STATUS_PENDING.equals(entry.getStatus()))
                .map(entry -> {
                    // The unique (device_id, status) index means a device can have
                    // at most one PROCESSING row, which serialises per device.
                    entry.setStatus(AiPositionOutbox.STATUS_PROCESSING);
                    entry.setAttempts(entry.getAttempts() + 1);
                    try {
                        return repository.saveAndFlush(entry);
                    } catch (DataIntegrityViolationException alreadyProcessing) {
                        return null;
                    }
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long id) {
        repository.deleteById(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, String reason) {
        repository.findById(id).ifPresent(entry -> {
            String safeReason = reason == null ? "unknown" : reason;
            entry.setLastError(safeReason.length() > 250 ? safeReason.substring(0, 250) : safeReason);
            if (entry.getAttempts() >= MAX_ATTEMPTS) {
                entry.setStatus(AiPositionOutbox.STATUS_FAILED);
                entry.setProcessedAt(Instant.now());
                DROPPED.incrementAndGet();
                log.warn("ai.outbox.dropped deviceId={} attempts={} reason={}",
                        entry.getDeviceId(), entry.getAttempts(), safeReason);
            } else {
                entry.setStatus(AiPositionOutbox.STATUS_PENDING);
            }
            repository.save(entry);
        });
    }

    public AiEvaluationPayload deserialise(AiPositionOutbox entry) {
        try {
            return objectMapper.readValue(entry.getPayloadJson(), AiEvaluationPayload.class);
        } catch (Exception ex) {
            log.warn("Could not read AI outbox payload {}: {}", entry.getId(), ex.getMessage());
            return null;
        }
    }

    public long pendingCount() {
        return repository.countByStatus(AiPositionOutbox.STATUS_PENDING);
    }
}
