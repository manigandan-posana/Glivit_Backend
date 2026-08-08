package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.glivt.ai.entity.AiEvent;
import com.glivt.ai.repository.AiEventRepository;
import com.glivt.ai.service.AiIncidentService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Incident deduplication: continuous speeding must update ONE incident rather
 * than insert a row per GPS packet, and severity must only ever escalate.
 *
 * <p>Not {@code @Transactional}: incident recording deliberately runs in its own
 * transaction so the next GPS packet sees it immediately, which means a test
 * rollback would not undo it. Rows are cleaned explicitly instead.
 */
@SpringBootTest
class AiIncidentServiceTest {

    private static final Long TENANT = 4001L;
    private static final Long OTHER_TENANT = 4002L;
    private static final Long VEHICLE = 501L;
    private static final Long DEVICE = 601L;

    @Autowired private AiIncidentService incidentService;
    @Autowired private AiEventRepository eventRepository;

    private Instant base;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-07-01T10:00:00Z");
        clearIncidents();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        clearIncidents();
    }

    private void clearIncidents() {
        for (Long tenantId : List.of(TENANT, OTHER_TENANT)) {
            eventRepository.deleteAll(eventRepository
                    .findByTenantIdAndCreatedAtAfterOrderByCreatedAtDesc(tenantId, Instant.EPOCH));
        }
    }

    private AiEvent candidate(String type, String severity, double score, Instant at) {
        return candidate(type, severity, score, at, TENANT, 12.9716, 77.5946);
    }

    private AiEvent candidate(String type, String severity, double score, Instant at, Long tenantId,
            double lat, double lng) {
        AiEvent event = new AiEvent();
        event.setTenantId(tenantId);
        event.setVehicleId(VEHICLE);
        event.setDeviceId(DEVICE);
        event.setEventType(type);
        event.setSeverity(severity);
        event.setScore(score);
        event.setLatitude(lat);
        event.setLongitude(lng);
        event.setSpeed(95.0);
        event.setCreatedAt(at);
        event.setExplanation(type + " detected");
        return event;
    }

    @Test
    void repeatedDetectionsUpdateOneIncidentInsteadOfFlooding() {
        // 30 packets of continuous speeding, 10 seconds apart.
        for (int i = 0; i < 30; i++) {
            incidentService.record(candidate("SPEEDING", "HIGH", 0.6,
                    base.plusSeconds(i * 10L)), Set.of());
        }

        List<AiEvent> stored = eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN);
        assertThat(stored).hasSize(1);
        AiEvent incident = stored.get(0);
        assertThat(incident.getOccurrenceCount()).isEqualTo(30);
        assertThat(incident.getFirstObservedAt()).isEqualTo(base);
        assertThat(incident.getLastObservedAt()).isEqualTo(base.plusSeconds(290));
    }

    @Test
    void firstDetectionCreatesAnIncident() {
        Optional<AiIncidentService.IncidentOutcome> outcome =
                incidentService.record(candidate("HARSH_BRAKING", "MEDIUM", 0.4, base), Set.of());

        assertThat(outcome).isPresent();
        assertThat(outcome.get().created()).isTrue();
        assertThat(outcome.get().escalated()).isFalse();
        assertThat(outcome.get().event().getStatus()).isEqualTo(AiEvent.STATUS_OPEN);
        assertThat(outcome.get().event().getFingerprint()).isNotBlank();
    }

    @Test
    void severityEscalatesButNeverDowngrades() {
        incidentService.record(candidate("SPEEDING", "MEDIUM", 0.3, base), Set.of());

        Optional<AiIncidentService.IncidentOutcome> escalation = incidentService.record(
                candidate("SPEEDING", "CRITICAL", 0.9, base.plusSeconds(30)), Set.of());
        assertThat(escalation).isPresent();
        assertThat(escalation.get().escalated()).isTrue();
        assertThat(escalation.get().event().getSeverity()).isEqualTo("CRITICAL");

        // A milder observation must not undo the escalation.
        Optional<AiIncidentService.IncidentOutcome> mild = incidentService.record(
                candidate("SPEEDING", "LOW", 0.26, base.plusSeconds(60)), Set.of());
        assertThat(mild).isPresent();
        assertThat(mild.get().escalated()).isFalse();
        assertThat(mild.get().event().getSeverity()).isEqualTo("CRITICAL");
        assertThat(mild.get().event().getMaxScore()).isEqualTo(0.9);
    }

    @Test
    void differentAnomalyTypesBecomeSeparateIncidents() {
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6, base), Set.of("HARSH_BRAKING"));
        incidentService.record(candidate("HARSH_BRAKING", "MEDIUM", 0.4, base), Set.of("SPEEDING"));

        List<AiEvent> stored = eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN);
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(AiEvent::getEventType)
                .containsExactlyInAnyOrder("SPEEDING", "HARSH_BRAKING");
        // Each incident records the other types seen on the same packet.
        assertThat(stored).allSatisfy(e -> assertThat(e.getRelatedEventTypes()).isNotBlank());
    }

    @Test
    void sameAnomalyInADifferentPlaceIsANewIncident() {
        incidentService.record(candidate("ROUTE_DEVIATION", "HIGH", 0.6, base), Set.of());
        // ~5 km away: a genuinely different deviation, not the same one.
        incidentService.record(candidate("ROUTE_DEVIATION", "HIGH", 0.6, base.plusSeconds(60),
                TENANT, 13.0500, 77.6500), Set.of());

        assertThat(eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN)).hasSize(2);
    }

    @Test
    void detectionAfterTheCooldownStartsAFreshIncident() {
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6, base), Set.of());
        // Well past the cooldown window.
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6,
                base.plus(AiIncidentService.COOLDOWN).plus(5, ChronoUnit.MINUTES)), Set.of());

        List<AiEvent> open = eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getOccurrenceCount()).isEqualTo(1);
        // The earlier incident was closed rather than reopened.
        assertThat(eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_RESOLVED)).hasSize(1);
    }

    @Test
    void fingerprintsAreDeterministicAndTenantSpecific() {
        String a = AiIncidentService.fingerprint(1L, 10L, 20L, "SPEEDING", 12.97, 77.59, 5L);
        String b = AiIncidentService.fingerprint(1L, 10L, 20L, "SPEEDING", 12.97, 77.59, 5L);
        String otherTenant = AiIncidentService.fingerprint(2L, 10L, 20L, "SPEEDING", 12.97, 77.59, 5L);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(otherTenant);
    }

    @Test
    void incidentsAreIsolatedPerTenant() {
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6, base), Set.of());
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6, base, OTHER_TENANT,
                12.9716, 77.5946), Set.of());

        // The same anomaly for two tenants is two incidents, each visible only
        // to its owner.
        assertThat(eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN)).hasSize(1);
        assertThat(eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                OTHER_TENANT, AiEvent.STATUS_OPEN)).hasSize(1);
    }

    @Test
    void staleIncidentsAreAutoResolved() {
        incidentService.record(candidate("SPEEDING", "HIGH", 0.6, base), Set.of());

        int resolved = incidentService.resolveStaleIncidents(TENANT,
                base.plus(AiIncidentService.AUTO_RESOLVE_AFTER).plus(1, ChronoUnit.MINUTES));

        assertThat(resolved).isEqualTo(1);
        assertThat(eventRepository.findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(
                TENANT, AiEvent.STATUS_OPEN)).isEmpty();
    }

    @Test
    void severityRankingIsMonotonic() {
        assertThat(AiIncidentService.severityRank("LOW"))
                .isLessThan(AiIncidentService.severityRank("MEDIUM"));
        assertThat(AiIncidentService.severityRank("MEDIUM"))
                .isLessThan(AiIncidentService.severityRank("HIGH"));
        assertThat(AiIncidentService.severityRank("HIGH"))
                .isLessThan(AiIncidentService.severityRank("CRITICAL"));
    }
}
