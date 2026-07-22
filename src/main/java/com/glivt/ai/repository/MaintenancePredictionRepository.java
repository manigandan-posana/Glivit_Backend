package com.glivt.ai.repository;

import com.glivt.ai.entity.MaintenancePrediction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaintenancePredictionRepository extends JpaRepository<MaintenancePrediction, Long> {

    List<MaintenancePrediction> findByTenantIdAndStatus(Long tenantId, String status);

    List<MaintenancePrediction> findByTenantIdOrderByRiskScoreDesc(Long tenantId);

    Optional<MaintenancePrediction> findFirstByTenantIdAndVehicleIdOrderByCreatedAtDesc(Long tenantId, Long vehicleId);

    long countByTenantIdAndRiskLevelInAndStatus(Long tenantId, List<String> riskLevels, String status);
}
