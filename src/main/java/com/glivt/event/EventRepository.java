package com.glivt.event;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select e from Event e
            where e.tenantId = :tenantId
              and (:deviceId is null or e.deviceId = :deviceId)
              and (:eventType is null or e.eventType = :eventType)
              and (:severity is null or e.severity = :severity)
              and (:fromTime is null or e.serverTime >= :fromTime)
              and (:toTime is null or e.serverTime <= :toTime)
            """)
    Page<Event> search(@Param("tenantId") Long tenantId,
                       @Param("deviceId") Long deviceId,
                       @Param("eventType") String eventType,
                       @Param("severity") String severity,
                       @Param("fromTime") Instant fromTime,
                       @Param("toTime") Instant toTime,
                       Pageable pageable);
}
