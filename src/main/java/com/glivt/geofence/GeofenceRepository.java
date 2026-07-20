package com.glivt.geofence;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceRepository extends JpaRepository<Geofence, Long> {

    Optional<Geofence> findByIdAndTenantId(Long id, Long tenantId);

    Page<Geofence> findByTenantId(Long tenantId, Pageable pageable);
}
