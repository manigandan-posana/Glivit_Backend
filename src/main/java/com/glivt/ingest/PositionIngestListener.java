package com.glivt.ingest;

import com.glivt.ai.service.AiAsyncEvaluatorService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed GPS points to asynchronous AI evaluation. Running AFTER_COMMIT
 * guarantees the position is durably stored before any anomaly scoring, and keeps
 * the AI work off the ingestion transaction/thread entirely.
 */
@Component
public class PositionIngestListener {

    private final AiAsyncEvaluatorService evaluator;

    public PositionIngestListener(AiAsyncEvaluatorService evaluator) {
        this.evaluator = evaluator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPositionIngested(PositionIngestedEvent event) {
        evaluator.evaluatePositionAsync(event.position(), event.features(), event.speedLimitKph());
    }
}
