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

    /** Paginated, newest-first history window for a device (tenant-scoped). */
    Page<Position> findByTenantIdAndDeviceIdAndDeviceTimeBetween(
            Long tenantId, Long deviceId, Instant from, Instant to, Pageable pageable);

    long countByTenantIdAndDeviceIdAndDeviceTimeBetween(
            Long tenantId, Long deviceId, Instant from, Instant to);
}
