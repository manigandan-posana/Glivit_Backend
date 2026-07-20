package com.glivt.position;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceCurrentPositionRepository extends JpaRepository<DeviceCurrentPosition, Long> {

    List<DeviceCurrentPosition> findByTenantId(Long tenantId);

    Optional<DeviceCurrentPosition> findByDeviceIdAndTenantId(Long deviceId, Long tenantId);

    interface StateCount {
        DeviceState getState();

        long getTotal();
    }

    @Query("""
            select p.state as state, count(p) as total
            from DeviceCurrentPosition p
            where p.tenantId = :tenantId
            group by p.state
            """)
    List<StateCount> countByStateForTenant(@Param("tenantId") Long tenantId);
}
