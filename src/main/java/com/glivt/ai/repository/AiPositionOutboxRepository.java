package com.glivt.ai.repository;

import com.glivt.ai.entity.AiPositionOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPositionOutboxRepository extends JpaRepository<AiPositionOutbox, Long> {

    Optional<AiPositionOutbox> findByDeviceIdAndStatus(Long deviceId, String status);

    @Query("""
            select o from AiPositionOutbox o
            where o.status = :status
            order by o.createdAt asc
            """)
    List<AiPositionOutbox> findBatch(@Param("status") String status, Pageable pageable);

    long countByStatus(String status);

    /** Requeues entries stuck in PROCESSING after a crash. */
    @Modifying
    @Query("""
            update AiPositionOutbox o set o.status = 'PENDING'
            where o.status = 'PROCESSING' and o.createdAt < :before
            """)
    int requeueStale(@Param("before") Instant before);

    @Modifying
    @Query("delete from AiPositionOutbox o where o.status = 'FAILED' and o.createdAt < :before")
    int purgeFailed(@Param("before") Instant before);
}
