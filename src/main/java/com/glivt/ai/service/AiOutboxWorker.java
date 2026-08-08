package com.glivt.ai.service;

import com.glivt.ai.config.AiAsyncConfig;
import com.glivt.ai.entity.AiPositionOutbox;
import com.glivt.ai.repository.AiPositionOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the AI evaluation outbox.
 *
 * <p>This is the only place anomaly scoring is triggered from, which is what
 * keeps GPS ingestion completely independent of Python and Ollama: ingestion
 * commits an outbox row and returns, and if the AI stack is down the rows simply
 * wait (or age out) without ever touching the ingestion path.
 *
 * <p>Batches are small and the poll interval is short, so live alerting still
 * feels immediate under normal conditions.
 */
@Component
public class AiOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(AiOutboxWorker.class);
    private static final int BATCH_SIZE = 25;
    private static final Duration STALE_PROCESSING = Duration.ofMinutes(5);

    private final AiPositionOutboxRepository repository;
    private final AiPositionOutboxService outboxService;
    private final AiAsyncEvaluatorService evaluator;

    public AiOutboxWorker(AiPositionOutboxRepository repository,
            AiPositionOutboxService outboxService,
            AiAsyncEvaluatorService evaluator) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.evaluator = evaluator;
    }

    @Scheduled(fixedDelayString = "${app.ai.outbox.poll-ms:2000}")
    public void drain() {
        List<AiPositionOutbox> batch;
        try {
            batch = repository.findBatch(AiPositionOutbox.STATUS_PENDING, PageRequest.of(0, BATCH_SIZE));
        } catch (Exception ex) {
            log.debug("Could not read AI outbox: {}", ex.getMessage());
            return;
        }
        if (batch.isEmpty()) {
            return;
        }

        for (AiPositionOutbox entry : batch) {
            AiPositionOutbox claimed = outboxService.claim(entry.getId()).orElse(null);
            if (claimed == null) {
                continue; // another instance took it
            }
            try {
                AiEvaluationPayload payload = outboxService.deserialise(claimed);
                if (payload == null) {
                    outboxService.complete(claimed.getId());
                    continue;
                }
                evaluator.evaluate(payload);
                outboxService.complete(claimed.getId());
            } catch (Exception ex) {
                // A failure here must never stop the rest of the batch.
                log.warn("ai.outbox.error entryId={} deviceId={} error={}",
                        claimed.getId(), claimed.getDeviceId(), ex.toString());
                outboxService.fail(claimed.getId(), ex.getClass().getSimpleName());
            }
        }
    }

    /** Requeues work abandoned by a crashed instance. */
    @Scheduled(fixedDelayString = "${app.ai.outbox.requeue-ms:60000}")
    public void requeueStale() {
        try {
            int requeued = repository.requeueStale(Instant.now().minus(STALE_PROCESSING));
            if (requeued > 0) {
                log.info("ai.outbox.requeued count={}", requeued);
            }
        } catch (Exception ex) {
            log.debug("Could not requeue stale AI outbox entries: {}", ex.getMessage());
        }
    }

    /** Metrics for the diagnostics endpoint. */
    public record OutboxMetrics(long pending, long coalesced, long dropped, long rejectedTasks) {
    }

    public OutboxMetrics metrics() {
        long pending;
        try {
            pending = repository.countByStatus(AiPositionOutbox.STATUS_PENDING);
        } catch (Exception ex) {
            pending = -1;
        }
        return new OutboxMetrics(pending,
                AiPositionOutboxService.COALESCED.get(),
                AiPositionOutboxService.DROPPED.get(),
                AiAsyncConfig.REJECTED_TASKS.get());
    }
}
