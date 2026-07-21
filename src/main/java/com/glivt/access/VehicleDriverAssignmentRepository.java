package com.glivt.access;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleDriverAssignmentRepository extends JpaRepository<VehicleDriverAssignment, Long> {

    @Query("""
            select a.vehicleId from VehicleDriverAssignment a
            where a.tenantId = :tenantId and a.active = true and a.driverId in :driverIds
            """)
    List<Long> activeVehicleIds(@Param("tenantId") Long tenantId,
                                @Param("driverIds") Collection<Long> driverIds);

    List<VehicleDriverAssignment> findByTenantIdAndVehicleIdAndActiveTrue(Long tenantId, Long vehicleId);
}
