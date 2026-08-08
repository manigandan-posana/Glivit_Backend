package com.glivt.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glivt.access.FleetAccessPolicy;
import com.glivt.ai.client.AiResult;
import com.glivt.ai.client.PythonAiClient;
import com.glivt.ai.config.AiProperties;
import com.glivt.ai.dto.SemanticSearchRequestDto;
import com.glivt.ai.dto.SemanticSearchResponseDto;
import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.entity.AiSemanticIndexEntry;
import com.glivt.ai.entity.MaintenancePrediction;
import com.glivt.ai.entity.TripFeatureSnapshot;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.repository.AiSemanticIndexRepository;
import com.glivt.ai.repository.MaintenancePredictionRepository;
import com.glivt.ai.repository.TripFeatureSnapshotRepository;
import com.glivt.event.Event;
import com.glivt.event.EventRepository;
import com.glivt.security.AppUserPrincipal;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Semantic search over real tenant-owned records.
 *
 * <p>The hard-coded incident list is gone. An indexing job writes AI events,
 * trips, operational alerts, maintenance predictions and route deviations into
 * {@code ai_semantic_index}, each row tagged with its tenant, vehicle and driver.
 *
 * <p>Isolation is enforced here, in SQL, <em>before</em> ranking: the candidate
 * query filters by the authenticated tenant and by the vehicles the caller is
 * allowed to see. The AI service only ever receives documents the caller may
 * already read, so it is structurally incapable of leaking another tenant's data.
 */
@Service
public class AiSemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(AiSemanticSearchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CANDIDATES = 500;
    private static final int EMBED_BATCH = 32;

    private final AiSemanticIndexRepository indexRepository;
    private final AiEventRepository aiEventRepository;
    private final EventRepository eventRepository;
    private final TripFeatureSnapshotRepository tripRepository;
    private final MaintenancePredictionRepository maintenanceRepository;
    private final VehicleRepository vehicleRepository;
    private final PythonAiClient pythonAiClient;
    private final AiProperties properties;
    private final FleetAccessPolicy fleetAccessPolicy;

    public AiSemanticSearchService(AiSemanticIndexRepository indexRepository,
            AiEventRepository aiEventRepository,
            EventRepository eventRepository,
            TripFeatureSnapshotRepository tripRepository,
            MaintenancePredictionRepository maintenanceRepository,
            VehicleRepository vehicleRepository,
            PythonAiClient pythonAiClient,
            AiProperties properties,
            FleetAccessPolicy fleetAccessPolicy) {
        this.indexRepository = indexRepository;
        this.aiEventRepository = aiEventRepository;
        this.eventRepository = eventRepository;
        this.tripRepository = tripRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.vehicleRepository = vehicleRepository;
        this.pythonAiClient = pythonAiClient;
        this.properties = properties;
        this.fleetAccessPolicy = fleetAccessPolicy;
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SemanticSearchResponseDto search(AppUserPrincipal user, SemanticSearchRequestDto request) {
        Long tenantId = user.getTenantId();

        // Role/assignment scoping: a driver only searches their own vehicles.
        FleetAccessPolicy.VehicleScope scope = fleetAccessPolicy.vehicleScope(user);
        boolean allVehicles = scope.unrestricted();
        // A non-empty placeholder keeps the "in (:vehicleIds)" clause valid when
        // the scope is unrestricted; the :allVehicles flag short-circuits it.
        Collection<Long> vehicleIds = allVehicles ? List.of(-1L) : scope.vehicleIds();
        if (!allVehicles && vehicleIds.isEmpty()) {
            return SemanticSearchResponseDto.builder()
                    .query(request.getQuery())
                    .matches(List.of())
                    .source("EMBEDDING")
                    .degraded(false)
                    .build();
        }

        List<AiSemanticIndexEntry> candidates = indexRepository.findCandidates(
                tenantId, allVehicles, vehicleIds, PageRequest.of(0, MAX_CANDIDATES));

        if (candidates.isEmpty()) {
            return SemanticSearchResponseDto.builder()
                    .query(request.getQuery())
                    .matches(List.of())
                    .source("EMBEDDING")
                    .degraded(false)
                    .build();
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        for (AiSemanticIndexEntry entry : candidates) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", entry.getSourceType() + ":" + entry.getSourceId());
            document.put("source_type", entry.getSourceType());
            document.put("source_id", entry.getSourceId());
            document.put("content", entry.getContent());
            document.put("metadata", parseMetadata(entry));
            document.put("embedding", parseEmbedding(entry.getEmbeddingJson()));
            documents.add(document);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId);
        payload.put("query", request.getQuery());
        payload.put("limit", Math.min(Math.max(request.getLimit(), 1), 50));
        payload.put("min_score", request.getMinScore() != null ? request.getMinScore() : 0.35);
        payload.put("documents", documents);

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/search/embeddings",
                payload, new PythonAiClient.AiCallOptions("search.semantic", tenantId, null,
                        properties.getPythonService().getEmbeddingTimeoutMs()));

        if (!result.success()) {
            return SemanticSearchResponseDto.builder()
                    .query(request.getQuery())
                    .matches(List.of())
                    .source("UNAVAILABLE")
                    .degraded(true)
                    .errorCode(result.errorCode().name())
                    .build();
        }
        return toResponse(request.getQuery(), result.payload(), tenantId);
    }

    @SuppressWarnings("unchecked")
    private SemanticSearchResponseDto toResponse(String query, Map<String, Object> body, Long tenantId) {
        List<SemanticSearchResponseDto.SearchMatchDto> matches = new ArrayList<>();
        if (body.get("matches") instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> match = (Map<String, Object>) raw;
                Long sourceId = match.get("source_id") instanceof Number number
                        ? number.longValue() : null;
                matches.add(SemanticSearchResponseDto.SearchMatchDto.builder()
                        .id(String.valueOf(match.get("id")))
                        .sourceType(String.valueOf(match.get("source_type")))
                        // The real source record id, so the UI can open it.
                        .sourceId(sourceId)
                        .score(match.get("score") instanceof Number number ? number.doubleValue() : 0)
                        .content(String.valueOf(match.get("content")))
                        .metadata(match.get("metadata") instanceof Map<?, ?> meta
                                ? (Map<String, Object>) meta : Map.of())
                        .build());
            }
        }
        boolean degraded = Boolean.TRUE.equals(body.get("degraded"));
        return SemanticSearchResponseDto.builder()
                .query(query)
                .matches(matches)
                .source(String.valueOf(body.getOrDefault("source", "EMBEDDING")))
                .degraded(degraded)
                .errorCode(body.get("error_code") == null ? null : String.valueOf(body.get("error_code")))
                .build();
    }

    // ------------------------------------------------------------------
    // Indexing
    // ------------------------------------------------------------------

    /**
     * Index (or refresh) a tenant's recent records. Called by the scheduled
     * indexing job, one tenant at a time.
     *
     * @return the number of rows written or updated
     */
    @Transactional
    public int indexTenant(Long tenantId, int lookbackDays) {
        Instant since = Instant.now().minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
        Map<Long, String> vehicleNames = new LinkedHashMap<>();
        for (Vehicle vehicle : vehicleRepository.findByTenantId(tenantId)) {
            vehicleNames.put(vehicle.getId(), vehicle.getName());
        }

        int written = 0;

        for (AiEvent event : aiEventRepository
                .findByTenantIdAndCreatedAtAfterOrderByCreatedAtDesc(tenantId, since)) {
            String vehicleName = vehicleNames.getOrDefault(event.getVehicleId(), "unassigned vehicle");
            StringBuilder content = new StringBuilder()
                    .append(humanise(event.getEventType())).append(" on ").append(vehicleName)
                    .append(" with ").append(event.getSeverity()).append(" severity");
            if (event.getExplanation() != null && !event.getExplanation().isBlank()) {
                content.append(". ").append(event.getExplanation());
            }
            if (event.getDistanceFromRouteMeters() != null) {
                content.append(String.format(java.util.Locale.ROOT,
                        " Distance from route: %.0f m.", event.getDistanceFromRouteMeters()));
            }
            written += upsert(tenantId, "AI_EVENT", event.getId(), event.getVehicleId(),
                    event.getDriverId(), content.toString(),
                    Map.of("severity", event.getSeverity(), "type", event.getEventType(),
                            "status", event.getStatus()),
                    event.getCreatedAt());
        }

        for (Event event : eventRepository.findByTenantIdAndServerTimeAfter(tenantId, since)) {
            String vehicleName = vehicleNames.getOrDefault(event.getVehicleId(), "unassigned vehicle");
            String content = humanise(event.getEventType()) + " alert on " + vehicleName
                    + (event.getDetail() == null || event.getDetail().isBlank()
                            ? "" : ". " + event.getDetail());
            written += upsert(tenantId, "ALERT", event.getId(), event.getVehicleId(), null, content,
                    Map.of("severity", String.valueOf(event.getSeverity())), event.getServerTime());
        }

        for (TripFeatureSnapshot trip : tripRepository
                .findByTenantIdAndStartTimeAfter(tenantId, since)) {
            String vehicleName = vehicleNames.getOrDefault(trip.getVehicleId(), "unassigned vehicle");
            String content = String.format(java.util.Locale.ROOT,
                    "Trip by %s covering %.1f km in %d minutes with %d harsh event(s), "
                            + "%d minute(s) idling and %d route deviation(s).",
                    vehicleName, trip.getDistanceKm(), trip.getDurationMinutes(),
                    trip.getHarshEventCount(), trip.getIdleDurationMinutes(),
                    trip.getRouteDeviationCount());
            written += upsert(tenantId, "TRIP", trip.getId(), trip.getVehicleId(), trip.getDriverId(),
                    content, Map.of("distanceKm", trip.getDistanceKm()), trip.getStartTime());
        }

        for (MaintenancePrediction prediction
                : maintenanceRepository.findByTenantIdOrderByRiskScoreDesc(tenantId)) {
            String vehicleName = vehicleNames.getOrDefault(prediction.getVehicleId(), "unassigned vehicle");
            String content = "Maintenance prediction for " + vehicleName + ": "
                    + prediction.getRiskLevel() + " risk"
                    + (prediction.getPredictedComponent() == null
                            ? "" : " on " + humanise(prediction.getPredictedComponent()))
                    + (prediction.getReasoning() == null ? "" : ". " + prediction.getReasoning());
            written += upsert(tenantId, "MAINTENANCE", prediction.getId(), prediction.getVehicleId(),
                    null, content, Map.of("riskLevel", prediction.getRiskLevel()),
                    prediction.getCreatedAt());
        }

        return written;
    }

    /** Generates embeddings for index rows that do not have one yet. */
    @Transactional
    public int embedPending() {
        List<AiSemanticIndexEntry> pending =
                indexRepository.findUnembedded(PageRequest.of(0, EMBED_BATCH));
        if (pending.isEmpty()) {
            return 0;
        }

        Map<String, Object> payload = Map.of(
                "tenant_id", pending.get(0).getTenantId(),
                "texts", pending.stream().map(AiSemanticIndexEntry::getContent).toList());

        AiResult<Map<String, Object>> result = pythonAiClient.postForMap("/v1/embeddings", payload,
                new PythonAiClient.AiCallOptions("search.embed", null, null,
                        properties.getPythonService().getEmbeddingTimeoutMs()));
        if (!result.success()) {
            return 0;
        }
        Map<String, Object> body = result.payload();
        if (!"EMBEDDING_MODEL".equals(String.valueOf(body.get("source")))
                || !(body.get("embeddings") instanceof List<?> vectors)
                || vectors.size() != pending.size()) {
            return 0;
        }

        String model = String.valueOf(body.get("model"));
        int updated = 0;
        for (int i = 0; i < pending.size(); i++) {
            Object vector = vectors.get(i);
            if (!(vector instanceof List<?> values)) {
                continue;
            }
            AiSemanticIndexEntry entry = pending.get(i);
            try {
                entry.setEmbeddingJson(MAPPER.writeValueAsString(values));
                entry.setEmbeddingModel(model);
                entry.setEmbeddingDim(values.size());
                indexRepository.save(entry);
                updated++;
            } catch (Exception ex) {
                log.debug("Could not store embedding for index entry {}: {}",
                        entry.getId(), ex.getMessage());
            }
        }
        return updated;
    }

    private int upsert(Long tenantId, String sourceType, Long sourceId, Long vehicleId,
            Long driverId, String content, Map<String, Object> metadata, Instant occurredAt) {
        if (sourceId == null || content == null || content.isBlank()) {
            return 0;
        }
        Optional<AiSemanticIndexEntry> existing = indexRepository
                .findByTenantIdAndSourceTypeAndSourceId(tenantId, sourceType, sourceId);

        AiSemanticIndexEntry entry = existing.orElseGet(AiSemanticIndexEntry::new);
        boolean contentChanged = !content.equals(entry.getContent());
        entry.setTenantId(tenantId);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setVehicleId(vehicleId);
        entry.setDriverId(driverId);
        entry.setContent(content);
        entry.setOccurredAt(occurredAt);
        try {
            entry.setMetadataJson(MAPPER.writeValueAsString(metadata));
        } catch (Exception ignored) {
            entry.setMetadataJson(null);
        }
        if (contentChanged) {
            // The text changed, so the stored vector no longer describes it.
            entry.setEmbeddingJson(null);
            entry.setEmbeddingDim(null);
        }
        indexRepository.save(entry);
        return 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(AiSemanticIndexEntry entry) {
        if (entry.getMetadataJson() == null || entry.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(entry.getMetadataJson(), Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<Double> parseEmbedding(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Double>>() {
                    });
        } catch (Exception ex) {
            return null;
        }
    }

    private static String humanise(String value) {
        if (value == null || value.isBlank()) {
            return "event";
        }
        return value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }
}
