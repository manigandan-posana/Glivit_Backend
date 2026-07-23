package com.glivt.position;

import com.glivt.ingest.PositionIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes each committed, in-order position to the tenant's live SSE stream.
 *
 * Runs AFTER_COMMIT so it never touches the ingest transaction, and only for
 * points that actually advanced the live location (out-of-order/late packets are
 * persisted for history but never move the live marker). Streaming failures are
 * swallowed: a broken SSE consumer must never affect GPS ingestion.
 */
@Component
public class LivePositionPublisher {

    private static final Logger log = LoggerFactory.getLogger(LivePositionPublisher.class);

    private final LivePositionBroadcaster broadcaster;
    private final DeviceCurrentPositionRepository currentPositionRepository;

    public LivePositionPublisher(LivePositionBroadcaster broadcaster,
                                 DeviceCurrentPositionRepository currentPositionRepository) {
        this.broadcaster = broadcaster;
        this.currentPositionRepository = currentPositionRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPositionIngested(PositionIngestedEvent event) {
        // Out-of-order points don't advance the live snapshot, so don't stream them.
        if (event.features().outOfOrder()) {
            return;
        }
        try {
            currentPositionRepository.findById(event.position().getDeviceId())
                    .ifPresent(current ->
                            broadcaster.broadcast(current.getTenantId(), LivePositionDto.from(current)));
        } catch (Exception ex) {
            log.warn("Live position broadcast failed for device {}", event.position().getDeviceId(), ex);
        }
    }
}
