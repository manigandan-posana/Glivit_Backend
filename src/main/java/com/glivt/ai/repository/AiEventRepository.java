package com.glivt.ai.repository;

import com.glivt.ai.entity.AiEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
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

    long countByTenantIdAndAcknowledgedFalse(Long tenantId);

    long countByTenantIdAndSeverityAndCreatedAtAfter(Long tenantId, String severity, Instant after);

    List<AiEvent> findTop10ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
