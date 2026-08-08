package com.glivt.route;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRouteRepository extends JpaRepository<VehicleRoute, Long> {

    Optional<VehicleRoute> findByIdAndTenantId(Long id, Long tenantId);

    List<VehicleRoute> findByTenantIdAndActiveTrue(Long tenantId);
}
