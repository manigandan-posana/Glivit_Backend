package com.glivt.vehicle;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByIdAndTenantId(Long id, Long tenantId);

    long countByTenantIdAndDriverId(Long tenantId, Long driverId);
}
