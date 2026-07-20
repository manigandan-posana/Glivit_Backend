package com.glivt.position;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {

    List<Position> findByTenantIdAndDeviceIdAndDeviceTimeBetweenOrderByDeviceTimeAsc(
            Long tenantId, Long deviceId, Instant from, Instant to);
}
