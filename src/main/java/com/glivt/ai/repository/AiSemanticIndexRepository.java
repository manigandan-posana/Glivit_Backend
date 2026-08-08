package com.glivt.ai.repository;

import com.glivt.ai.entity.AiSemanticIndexEntry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiSemanticIndexRepository extends JpaRepository<AiSemanticIndexEntry, Long> {

    Optional<AiSemanticIndexEntry> findByTenantIdAndSourceTypeAndSourceId(
            Long tenantId, String sourceType, Long sourceId);

    /**
     * Search candidates for a tenant. Tenant filtering happens in the query, so
     * another tenant's rows can never reach the ranker.
     */
    @Query("""
            select e from AiSemanticIndexEntry e
            where e.tenantId = :tenantId
              and (:allVehicles = true or e.vehicleId is null or e.vehicleId in :vehicleIds)
            order by e.occurredAt desc nulls last, e.id desc
            """)
    List<AiSemanticIndexEntry> findCandidates(@Param("tenantId") Long tenantId,
                                              @Param("allVehicles") boolean allVehicles,
                                              @Param("vehicleIds") Collection<Long> vehicleIds,
                                              Pageable pageable);

    @Query("select e from AiSemanticIndexEntry e where e.embeddingJson is null order by e.id asc")
    List<AiSemanticIndexEntry> findUnembedded(Pageable pageable);

    long countByTenantId(Long tenantId);

    @Modifying
    @Query("delete from AiSemanticIndexEntry e where e.createdAt < :before")
    int purgeOlderThan(@Param("before") Instant before);
}
