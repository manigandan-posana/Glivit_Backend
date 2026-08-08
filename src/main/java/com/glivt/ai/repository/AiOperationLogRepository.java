package com.glivt.ai.repository;

import com.glivt.ai.entity.AiOperationLog;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiOperationLogRepository extends JpaRepository<AiOperationLog, Long> {

    List<AiOperationLog> findByTenantIdAndOperationOrderByCreatedAtDesc(Long tenantId, String operation);

    @Query("""
            select l.operation as operation, l.source as source, count(l) as total,
                   avg(l.processingMs) as avgMs
            from AiOperationLog l
            where (:tenantId is null or l.tenantId = :tenantId) and l.createdAt >= :since
            group by l.operation, l.source
            """)
    List<OperationStat> summarise(@Param("tenantId") Long tenantId, @Param("since") Instant since);

    interface OperationStat {
        String getOperation();

        String getSource();

        long getTotal();

        Double getAvgMs();
    }

    @Modifying
    @Query("delete from AiOperationLog l where l.createdAt < :before")
    int purgeOlderThan(@Param("before") Instant before);
}
