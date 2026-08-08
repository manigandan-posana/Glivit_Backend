package com.glivt.ai.scheduler;

import com.glivt.ai.repository.AiPositionOutboxRepository;
import com.glivt.ai.service.AiGovernanceService;
import com.glivt.ai.service.AiIncidentService;
import com.glivt.ai.service.AiSemanticSearchService;
import com.glivt.ai.service.DriverScoringService;
import com.glivt.ai.service.GeofenceSuggestionService;
import com.glivt.ai.service.MaintenancePredictionService;
import com.glivt.ai.service.TripFeatureExtractionService;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantRepository;
import com.glivt.tenant.TenantStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background AI processing.
 *
 * <p>Every job is:
 * <ul>
 *   <li><b>Leader-elected</b> via {@link JobLockService}, so running several
 *       backend instances does not duplicate work;</li>
 *   <li><b>Tenant-batched</b> - tenants are processed one at a time and a failure
 *       in one is logged and skipped rather than aborting the rest;</li>
 *   <li><b>Independent of request threads</b>, so nothing here can slow down GPS
 *       ingestion or the live map.</li>
 * </ul>
 */
@Component
public class AiScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(AiScheduledJobs.class);

    private final JobLockService lockService;
    private final TenantRepository tenantRepository;
    private final TripFeatureExtractionService tripExtractionService;
    private final DriverScoringService driverScoringService;
    private final MaintenancePredictionService maintenanceService;
    private final GeofenceSuggestionService geofenceSuggestionService;
    private final AiSemanticSearchService semanticSearchService;
    private final AiIncidentService incidentService;
    private final AiGovernanceService governanceService;
    private final AiPositionOutboxRepository outboxRepository;

    @Value("${app.ai.jobs.enabled:true}")
    private boolean jobsEnabled;

    @Value("${app.ai.jobs.retention-days:120}")
    private int retentionDays;

    public AiScheduledJobs(JobLockService lockService,
            TenantRepository tenantRepository,
            TripFeatureExtractionService tripExtractionService,
            DriverScoringService driverScoringService,
            MaintenancePredictionService maintenanceService,
            GeofenceSuggestionService geofenceSuggestionService,
            AiSemanticSearchService semanticSearchService,
            AiIncidentService incidentService,
            AiGovernanceService governanceService,
            AiPositionOutboxRepository outboxRepository) {
        this.lockService = lockService;
        this.tenantRepository = tenantRepository;
        this.tripExtractionService = tripExtractionService;
        this.driverScoringService = driverScoringService;
        this.maintenanceService = maintenanceService;
        this.geofenceSuggestionService = geofenceSuggestionService;
        this.semanticSearchService = semanticSearchService;
        this.incidentService = incidentService;
        this.governanceService = governanceService;
        this.outboxRepository = outboxRepository;
    }

    // ------------------------------------------------------------------
    // Trip feature extraction - hourly
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.trip-extraction-cron:0 10 * * * *}")
    public void extractTripFeatures() {
        run("ai-trip-extraction", Duration.ofMinutes(30), tenantId -> {
            Instant to = Instant.now();
            Instant from = to.minus(6, ChronoUnit.HOURS);
            return tripExtractionService.extractForTenant(tenantId, from, to);
        }, "trips");
    }

    // ------------------------------------------------------------------
    // Driver scoring - daily, just after midnight UTC for the previous day
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.driver-score-cron:0 20 0 * * *}")
    public void generateDriverScores() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        run("ai-driver-scoring", Duration.ofHours(1),
                tenantId -> driverScoringService.scoreTenant(tenantId, yesterday), "driverScores");
    }

    // ------------------------------------------------------------------
    // Maintenance prediction - daily
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.maintenance-cron:0 40 1 * * *}")
    public void refreshMaintenancePredictions() {
        run("ai-maintenance", Duration.ofHours(1),
                maintenanceService::evaluateTenant, "maintenancePredictions");
    }

    // ------------------------------------------------------------------
    // Geofence suggestions - weekly
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.geofence-cron:0 0 3 * * SUN}")
    public void generateGeofenceSuggestions() {
        run("ai-geofence-suggestions", Duration.ofHours(1),
                tenantId -> geofenceSuggestionService.generateForTenant(tenantId, 30),
                "geofenceSuggestions");
    }

    // ------------------------------------------------------------------
    // Semantic index - every 15 minutes, then embed in the background
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.semantic-index-cron:0 5/15 * * * *}")
    public void refreshSemanticIndex() {
        run("ai-semantic-index", Duration.ofMinutes(10),
                tenantId -> semanticSearchService.indexTenant(tenantId, 30), "indexedRecords");
    }

    @Scheduled(fixedDelayString = "${app.ai.jobs.embedding-delay-ms:60000}")
    public void embedPendingIndexEntries() {
        if (!jobsEnabled) {
            return;
        }
        lockService.runIfLeader("ai-semantic-embed", Duration.ofMinutes(5), () -> {
            int embedded = semanticSearchService.embedPending();
            if (embedded > 0) {
                log.info("job.semanticEmbed embedded={}", embedded);
            }
        });
    }

    // ------------------------------------------------------------------
    // Incident resolution and cleanup
    // ------------------------------------------------------------------

    @Scheduled(cron = "${app.ai.jobs.incident-resolution-cron:0 */10 * * * *}")
    public void resolveStaleIncidents() {
        run("ai-incident-resolution", Duration.ofMinutes(10),
                tenantId -> incidentService.resolveStaleIncidents(tenantId, Instant.now()),
                "resolvedIncidents");
    }

    @Scheduled(cron = "${app.ai.jobs.cleanup-cron:0 30 2 * * *}")
    public void cleanupStaleData() {
        if (!jobsEnabled) {
            return;
        }
        lockService.runIfLeader("ai-cleanup", Duration.ofMinutes(30), () -> {
            Instant before = Instant.now().minus(Math.max(7, retentionDays), ChronoUnit.DAYS);
            int purgedLogs = governanceService.purgeOlderThan(before);
            int purgedOutbox = outboxRepository.purgeFailed(Instant.now().minus(7, ChronoUnit.DAYS));
            log.info("job.cleanup purgedOperationLogs={} purgedFailedOutbox={}",
                    purgedLogs, purgedOutbox);
        });
    }

    // ------------------------------------------------------------------
    // Shared tenant-batch runner
    // ------------------------------------------------------------------

    private void run(String jobName, Duration lease, ToIntFunction<Long> perTenant, String unit) {
        if (!jobsEnabled) {
            return;
        }
        lockService.runIfLeader(jobName, lease, () -> {
            List<Tenant> tenants = tenantRepository.findAll().stream()
                    .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                    .toList();
            int total = 0;
            int failed = 0;
            for (Tenant tenant : tenants) {
                try {
                    total += perTenant.applyAsInt(tenant.getId());
                } catch (Exception ex) {
                    // Isolate tenants: record the failure and carry on.
                    failed++;
                    log.warn("job.tenantFailed name={} tenantId={} error={}",
                            jobName, tenant.getId(), ex.toString());
                }
            }
            log.info("job.summary name={} tenants={} {}={} failedTenants={}",
                    jobName, tenants.size(), unit, total, failed);
        });
    }
}
