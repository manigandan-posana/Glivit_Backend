package com.glivt.ai.service;

import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.repository.AiEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a stream of per-packet anomaly detections into a small number of
 * incidents.
 *
 * <p>Previously every GPS packet that scored above the threshold inserted a new
 * {@code ai_event} row, so a vehicle speeding for ten minutes at a 10-second
 * reporting interval produced sixty near-identical alerts. Here:
 *
 * <ul>
 *   <li>a deterministic fingerprint identifies "the same thing happening again";</li>
 *   <li>an OPEN incident with a matching fingerprint inside the cooldown window
 *       is <em>updated</em> - occurrence count, last-observed time and severity
 *       escalate - rather than duplicated;</li>
 *   <li>severity only ever escalates, so a brief dip does not downgrade a
 *       critical incident;</li>
 *   <li>incidents move OPEN -> ACKNOWLEDGED -> RESOLVED and are auto-resolved
 *       once they stop recurring.</li>
 * </ul>
 */
@Service
public class AiIncidentService {

    private static final Logger log = LoggerFactory.getLogger(AiIncidentService.class);

    /** Repeat detections inside this window fold into the same incident. */
    public static final Duration COOLDOWN = Duration.ofMinutes(10);
    /** An OPEN incident with no new observation for this long is auto-resolved. */
    public static final Duration AUTO_RESOLVE_AFTER = Duration.ofMinutes(30);

    private static final List<String> SEVERITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final AiEventRepository repository;

    public AiIncidentService(AiEventRepository repository) {
        this.repository = repository;
    }

    /** Outcome of recording one detection. */
    public record IncidentOutcome(AiEvent event, boolean created, boolean escalated) {
    }

    /**
     * A deterministic dedup key.
     *
     * <p>Includes a coarse location bucket (~1.1 km) so the same anomaly type
     * recurring in a different place is a genuinely new incident, while a vehicle
     * speeding continuously along one stretch of road stays one incident. The
     * assigned route is included so leaving and re-entering a route restarts the
     * deviation incident.
     */
    public static String fingerprint(Long tenantId, Long vehicleId, Long deviceId, String eventType,
            Double latitude, Double longitude, Long routeId) {
        String locationBucket = latitude == null || longitude == null
                ? "no-fix"
                : String.format(java.util.Locale.ROOT, "%.2f:%.2f", latitude, longitude);
        String raw = String.join("|",
                String.valueOf(tenantId),
                String.valueOf(vehicleId),
                String.valueOf(deviceId),
                String.valueOf(eventType),
                locationBucket,
                String.valueOf(routeId));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * Record a detection, creating a new incident or folding it into the active
     * one.
     *
     * <p>Runs in its own transaction: incident bookkeeping must never roll back
     * the caller, and must be visible immediately to the next packet.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IncidentOutcome> record(AiEvent candidate, Set<String> relatedTypes) {
        Instant observedAt = candidate.getCreatedAt() != null ? candidate.getCreatedAt() : Instant.now();
        String fingerprint = fingerprint(candidate.getTenantId(), candidate.getVehicleId(),
                candidate.getDeviceId(), candidate.getEventType(), candidate.getLatitude(),
                candidate.getLongitude(), candidate.getRouteId());
        candidate.setFingerprint(fingerprint);

        Optional<AiEvent> existing = repository
                .findFirstByTenantIdAndFingerprintAndStatusOrderByLastObservedAtDesc(
                        candidate.getTenantId(), fingerprint, AiEvent.STATUS_OPEN);

        if (existing.isPresent()) {
            AiEvent incident = existing.get();
            Instant lastObserved = incident.getLastObservedAt() != null
                    ? incident.getLastObservedAt()
                    : incident.getCreatedAt();

            if (lastObserved != null && Duration.between(lastObserved, observedAt).compareTo(COOLDOWN) > 0) {
                // The incident went quiet for longer than the cooldown: close it
                // and start a fresh one so the timeline stays meaningful.
                incident.setStatus(AiEvent.STATUS_RESOLVED);
                incident.setResolvedAt(lastObserved);
                repository.save(incident);
            } else {
                boolean escalated = mergeInto(incident, candidate, relatedTypes, observedAt);
                AiEvent saved = repository.save(incident);
                log.debug("ai.incident.update tenantId={} vehicleId={} type={} occurrences={} escalated={}",
                        saved.getTenantId(), saved.getVehicleId(), saved.getEventType(),
                        saved.getOccurrenceCount(), escalated);
                return Optional.of(new IncidentOutcome(saved, false, escalated));
            }
        }

        candidate.setStatus(AiEvent.STATUS_OPEN);
        candidate.setOccurrenceCount(1);
        candidate.setFirstObservedAt(observedAt);
        candidate.setLastObservedAt(observedAt);
        candidate.setMaxScore(candidate.getScore());
        if (relatedTypes != null && !relatedTypes.isEmpty()) {
            candidate.setRelatedEventTypes(String.join(",", relatedTypes));
        }
        try {
            AiEvent saved = repository.save(candidate);
            log.debug("ai.incident.create tenantId={} vehicleId={} type={} severity={}",
                    saved.getTenantId(), saved.getVehicleId(), saved.getEventType(),
                    saved.getSeverity());
            return Optional.of(new IncidentOutcome(saved, true, false));
        } catch (DataIntegrityViolationException race) {
            // Another worker created the same incident concurrently; fold into it.
            return repository
                    .findFirstByTenantIdAndFingerprintAndStatusOrderByLastObservedAtDesc(
                            candidate.getTenantId(), fingerprint, AiEvent.STATUS_OPEN)
                    .map(incident -> {
                        boolean escalated = mergeInto(incident, candidate, relatedTypes, observedAt);
                        return new IncidentOutcome(repository.save(incident), false, escalated);
                    });
        }
    }

    private boolean mergeInto(AiEvent incident, AiEvent candidate, Set<String> relatedTypes,
            Instant observedAt) {
        incident.setOccurrenceCount(incident.getOccurrenceCount() + 1);
        incident.setLastObservedAt(observedAt);
        incident.setSpeed(candidate.getSpeed());
        incident.setLatitude(candidate.getLatitude());
        incident.setLongitude(candidate.getLongitude());
        if (candidate.getDriverId() != null) {
            incident.setDriverId(candidate.getDriverId());
        }
        if (candidate.getDistanceFromRouteMeters() != null) {
            incident.setDistanceFromRouteMeters(candidate.getDistanceFromRouteMeters());
        }

        boolean escalated = false;
        if (candidate.getScore() > incident.getMaxScore()) {
            incident.setMaxScore(candidate.getScore());
            incident.setScore(candidate.getScore());
        }
        if (severityRank(candidate.getSeverity()) > severityRank(incident.getSeverity())) {
            incident.setSeverity(candidate.getSeverity());
            escalated = true;
            // An escalation deserves the operator's attention again.
            if (AiEvent.STATUS_ACKNOWLEDGED.equals(incident.getStatus())) {
                incident.setStatus(AiEvent.STATUS_OPEN);
                incident.setAcknowledged(false);
            }
        }
        if (candidate.getExplanation() != null && !candidate.getExplanation().isBlank()) {
            incident.setExplanation(candidate.getExplanation());
        }
        if (candidate.getEvidenceJson() != null) {
            incident.setEvidenceJson(candidate.getEvidenceJson());
        }
        if (relatedTypes != null && !relatedTypes.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>();
            if (incident.getRelatedEventTypes() != null && !incident.getRelatedEventTypes().isBlank()) {
                merged.addAll(List.of(incident.getRelatedEventTypes().split(",")));
            }
            merged.addAll(relatedTypes);
            String joined = String.join(",", merged);
            incident.setRelatedEventTypes(joined.length() > 512 ? joined.substring(0, 512) : joined);
        }
        return escalated;
    }

    /** Auto-resolves OPEN/ACKNOWLEDGED incidents that stopped recurring. */
    @Transactional
    public int resolveStaleIncidents(Long tenantId, Instant now) {
        Instant cutoff = now.minus(AUTO_RESOLVE_AFTER);
        List<AiEvent> stale = repository.findStaleOpenIncidents(tenantId, cutoff);
        for (AiEvent incident : stale) {
            incident.setStatus(AiEvent.STATUS_RESOLVED);
            incident.setResolvedAt(now);
        }
        if (!stale.isEmpty()) {
            repository.saveAll(stale);
            log.info("ai.incident.autoResolve tenantId={} resolved={}", tenantId, stale.size());
        }
        return stale.size();
    }

    public static int severityRank(String severity) {
        int index = SEVERITY_ORDER.indexOf(severity == null ? "LOW" : severity.toUpperCase(java.util.Locale.ROOT));
        return index < 0 ? 0 : index;
    }

    /** Severity ordering exposed for the dashboard and tests. */
    public static Map<String, Integer> severityRanks() {
        return Map.of("LOW", 0, "MEDIUM", 1, "HIGH", 2, "CRITICAL", 3);
    }
}
