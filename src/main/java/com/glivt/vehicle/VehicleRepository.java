package com.glivt.vehicle;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByIdAndTenantId(Long id, Long tenantId);

    /** Tenant-scoped vehicle list. Never use findAll() for tenant data. */
    List<Vehicle> findByTenantId(Long tenantId);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndDriverId(Long tenantId, Long driverId);
}
