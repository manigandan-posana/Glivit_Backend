package com.glivt.ai.repository;

import com.glivt.ai.entity.TripFeatureSnapshot;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TripFeatureSnapshotRepository extends JpaRepository<TripFeatureSnapshot, Long> {

    List<TripFeatureSnapshot> findByTenantIdAndVehicleIdOrderByStartTimeDesc(Long tenantId, Long vehicleId);

    Page<TripFeatureSnapshot> findByTenantId(Long tenantId, Pageable pageable);
}
