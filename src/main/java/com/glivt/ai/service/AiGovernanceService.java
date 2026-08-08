package com.glivt.ai.service;

import com.glivt.ai.entity.AiOperationLog;
import com.glivt.ai.repository.AiFeedbackRepository;
import com.glivt.ai.repository.AiModelRegistryRepository;
import com.glivt.ai.repository.AiOperationLogRepository;
import com.glivt.ai.repository.AiPromptVersionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model, prompt and rule-version governance.
 *
 * <p>Every AI result records what produced it, so a stored prediction can be
 * reproduced and an evaluation report can be built later. User feedback is
 * aggregated into that report but is deliberately never used to retrain a model
 * or move a production threshold automatically - a human reviews it first.
 */
@Service
public class AiGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AiGovernanceService.class);

    private final AiOperationLogRepository operationLogRepository;
    private final AiFeedbackRepository feedbackRepository;
    private final AiModelRegistryRepository modelRegistryRepository;
    private final AiPromptVersionRepository promptVersionRepository;

    public AiGovernanceService(AiOperationLogRepository operationLogRepository,
            AiFeedbackRepository feedbackRepository,
            AiModelRegistryRepository modelRegistryRepository,
            AiPromptVersionRepository promptVersionRepository) {
        this.operationLogRepository = operationLogRepository;
        this.feedbackRepository = feedbackRepository;
        this.modelRegistryRepository = modelRegistryRepository;
        this.promptVersionRepository = promptVersionRepository;
    }

    /**
     * Record the provenance of one AI result.
     *
     * <p>Uses its own transaction and swallows failures: governance bookkeeping
     * must never fail the operation it is describing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tenantId, String operation, String source, String modelName,
            String modelVersion, String ruleVersion, long processingMs, String fallbackReason,
            String referenceType, Long referenceId) {
        try {
            AiOperationLog entry = new AiOperationLog();
            entry.setTenantId(tenantId);
            entry.setOperation(operation);
            entry.setSource(source == null ? "RULE" : source);
            entry.setModelName(modelName);
            entry.setModelVersion(modelVersion);
            entry.setRuleVersion(ruleVersion);
            entry.setProcessingMs(processingMs);
            entry.setFallbackReason(fallbackReason);
            entry.setReferenceType(referenceType);
            entry.setReferenceId(referenceId);
            operationLogRepository.save(entry);
        } catch (Exception ex) {
            log.debug("Could not record AI operation log for {}: {}", operation, ex.getMessage());
        }
    }

    /** Overload for LLM-backed operations, which also carry a prompt version. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWithPrompt(Long tenantId, String operation, String source, String modelName,
            String promptVersion, long processingMs, String fallbackReason) {
        try {
            AiOperationLog entry = new AiOperationLog();
            entry.setTenantId(tenantId);
            entry.setOperation(operation);
            entry.setSource(source == null ? "RULE" : source);
            entry.setModelName(modelName);
            entry.setPromptVersion(promptVersion);
            entry.setProcessingMs(processingMs);
            entry.setFallbackReason(fallbackReason);
            operationLogRepository.save(entry);
        } catch (Exception ex) {
            log.debug("Could not record AI operation log for {}: {}", operation, ex.getMessage());
        }
    }

    /**
     * Evaluation report over the last {@code days} days: how often each operation
     * ran, from which source, how long it took, and what users said about it.
     *
     * <p>Read-only by design. Nothing here feeds back into thresholds or training.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluationReport(Long tenantId, int days) {
        Instant since = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);

        List<Map<String, Object>> operations = new ArrayList<>();
        for (AiOperationLogRepository.OperationStat stat : operationLogRepository.summarise(tenantId, since)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", stat.getOperation());
            row.put("source", stat.getSource());
            row.put("count", stat.getTotal());
            row.put("averageMs", stat.getAvgMs() == null ? 0 : Math.round(stat.getAvgMs()));
            operations.add(row);
        }

        long agreed = feedbackRepository.countByTenantIdAndCorrectTrue(tenantId);
        long disagreed = feedbackRepository.countByTenantIdAndCorrectFalse(tenantId);
        long totalFeedback = agreed + disagreed;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("windowDays", days);
        report.put("generatedAt", Instant.now().toString());
        report.put("operations", operations);
        report.put("feedback", Map.of(
                "agreed", agreed,
                "disagreed", disagreed,
                "total", totalFeedback,
                "agreementRate", totalFeedback == 0 ? null
                        : Math.round((agreed * 1000.0) / totalFeedback) / 10.0));
        report.put("registeredModels", modelRegistryRepository.count());
        report.put("promptVersions", promptVersionRepository.count());
        report.put("note", "Feedback is reported for human review only. Thresholds and models are "
                + "never changed automatically from user feedback.");
        return report;
    }

    @Transactional
    public int purgeOlderThan(Instant before) {
        return operationLogRepository.purgeOlderThan(before);
    }
}
