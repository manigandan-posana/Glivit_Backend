package com.glivt.geofence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeofenceRepository extends JpaRepository<Geofence, Long> {

    Optional<Geofence> findByIdAndTenantId(Long id, Long tenantId);

    /** Tenant-scoped active geofences; used for server-side speed-limit rules. */
    List<Geofence> findByTenantIdAndActiveTrue(Long tenantId);

    long countByTenantId(Long tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(Long tenantId, String name, Long id);

    Page<Geofence> findByTenantId(Long tenantId, Pageable pageable);
}
