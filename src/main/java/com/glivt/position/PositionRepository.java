package com.glivt.position;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(
            Long tenantId, Long deviceId, Instant from, Instant to);

    /** Idempotency guard for telemetry ingestion. */
    boolean existsByDeviceIdAndDedupKey(Long deviceId, String dedupKey);

    long countByTenantId(Long tenantId);

    /** Paginated, newest-first history window for a device (tenant-scoped). */
    Page<Position> findByTenantIdAndDeviceIdAndDeviceTimeBetween(
            Long tenantId, Long deviceId, Instant from, Instant to, Pageable pageable);

    long countByTenantIdAndDeviceIdAndDeviceTimeBetween(
            Long tenantId, Long deviceId, Instant from, Instant to);

    /**
     * Low-speed points for one tenant, ordered so consecutive readings at the
     * same place can be collapsed into stops for geofence clustering.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Position p
            where p.tenantId = :tenantId
              and p.deviceTime >= :since
              and p.speed <= :maxSpeed
              and p.gpsValid = true
            order by p.deviceId asc, p.deviceTime asc
            """)
    List<Position> findStopCandidates(
            @org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("since") Instant since,
            @org.springframework.data.repository.query.Param("maxSpeed") double maxSpeed,
            Pageable pageable);

    /** Positions for one device in a window, used for trip feature extraction. */
    List<Position> findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(
            Long tenantId, Long deviceId, Instant from, Instant to, Pageable pageable);
}
