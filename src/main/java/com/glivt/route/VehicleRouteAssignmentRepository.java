package com.glivt.route;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRouteAssignmentRepository extends JpaRepository<VehicleRouteAssignment, Long> {

    /**
     * The route a vehicle was running at {@code at}, resolved strictly within the
     * tenant. Ordered newest-first so the most recent assignment wins when
     * several overlap.
     */
    @Query("""
            select a from VehicleRouteAssignment a
            where a.tenantId = :tenantId
              and a.vehicleId = :vehicleId
              and a.active = true
              and a.startTime <= :at
              and (a.endTime is null or a.endTime >= :at)
            order by a.startTime desc
            """)
    List<VehicleRouteAssignment> findActiveAt(@Param("tenantId") Long tenantId,
                                              @Param("vehicleId") Long vehicleId,
                                              @Param("at") Instant at);

    List<VehicleRouteAssignment> findByTenantIdAndVehicleIdAndActiveTrue(Long tenantId, Long vehicleId);
}
