package com.glivt.ai.repository;

import com.glivt.ai.entity.AiEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface AiEventRepository extends JpaRepository<AiEvent, Long> {

    Optional<AiEvent> findByIdAndTenantId(Long id, Long tenantId);

    Page<AiEvent> findByTenantId(Long tenantId, Pageable pageable);

    Page<AiEvent> findByTenantIdAndVehicleId(Long tenantId, Long vehicleId, Pageable pageable);

    @Query("SELECT e FROM AiEvent e WHERE e.tenantId = :tenantId "
            + "AND (:vehicleId IS NULL OR e.vehicleId = :vehicleId) "
            + "AND (:severity IS NULL OR e.severity = :severity) "
            + "AND (:eventType IS NULL OR e.eventType = :eventType) "
            + "ORDER BY e.createdAt DESC")
    Page<AiEvent> findFiltered(@Param("tenantId") Long tenantId,
                               @Param("vehicleId") Long vehicleId,
                               @Param("severity") String severity,
                               @Param("eventType") String eventType,
                               Pageable pageable);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndAcknowledgedFalse(Long tenantId);

    long countByTenantIdAndSeverityAndCreatedAtAfter(Long tenantId, String severity, Instant after);

    List<AiEvent> findTop10ByTenantIdOrderByCreatedAtDesc(Long tenantId);

    // ------------------------------------------------------------------
    // Incident lifecycle
    // ------------------------------------------------------------------

    /** The active incident matching a dedup fingerprint, if any. */
    Optional<AiEvent> findFirstByTenantIdAndFingerprintAndStatusOrderByLastObservedAtDesc(
            Long tenantId, String fingerprint, String status);

    @Query("""
            select e from AiEvent e
            where (:tenantId is null or e.tenantId = :tenantId)
              and e.status in ('OPEN', 'ACKNOWLEDGED')
              and coalesce(e.lastObservedAt, e.createdAt) < :cutoff
            """)
    List<AiEvent> findStaleOpenIncidents(@Param("tenantId") Long tenantId,
                                         @Param("cutoff") Instant cutoff);

    /** Recent incidents for a vehicle, newest first - used to build chat context. */
    List<AiEvent> findTop5ByTenantIdAndVehicleIdOrderByCreatedAtDesc(Long tenantId, Long vehicleId);

    List<AiEvent> findTop20ByTenantIdAndStatusOrderByLastObservedAtDesc(Long tenantId, String status);

    /** Anomaly counts per event type for a driver over a period - driver scoring. */
    @Query("""
            select e.eventType as eventType, count(e) as total
            from AiEvent e
            where e.tenantId = :tenantId and e.driverId = :driverId
              and e.createdAt >= :from and e.createdAt < :to
            group by e.eventType
            """)
    List<EventTypeCount> countByTypeForDriver(@Param("tenantId") Long tenantId,
                                              @Param("driverId") Long driverId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    interface EventTypeCount {
        String getEventType();

        long getTotal();
    }

    @Query("""
            select count(e) from AiEvent e
            where e.tenantId = :tenantId and e.vehicleId = :vehicleId
              and e.eventType in :types and e.createdAt >= :since
            """)
    long countByVehicleAndTypesSince(@Param("tenantId") Long tenantId,
                                     @Param("vehicleId") Long vehicleId,
                                     @Param("types") List<String> types,
                                     @Param("since") Instant since);

    List<AiEvent> findByTenantIdAndCreatedAtAfterOrderByCreatedAtDesc(Long tenantId, Instant after);

    @Modifying
    @Query("delete from AiEvent e where e.status = 'RESOLVED' and e.createdAt < :before")
    int purgeResolvedBefore(@Param("before") Instant before);
}
