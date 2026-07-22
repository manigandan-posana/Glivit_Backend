package com.glivt.ai.repository;

import com.glivt.ai.entity.DispatchRecommendation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DispatchRecommendationRepository extends JpaRepository<DispatchRecommendation, Long> {

    List<DispatchRecommendation> findTop10ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
