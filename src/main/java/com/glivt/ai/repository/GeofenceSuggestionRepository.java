package com.glivt.ai.repository;

import com.glivt.ai.entity.GeofenceSuggestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GeofenceSuggestionRepository extends JpaRepository<GeofenceSuggestion, Long> {

    List<GeofenceSuggestion> findByTenantIdAndStatus(Long tenantId, String status);

    Optional<GeofenceSuggestion> findByIdAndTenantId(Long id, Long tenantId);
}
