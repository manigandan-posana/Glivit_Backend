package com.glivt.report;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<ReportJob, Long> {

    Optional<ReportJob> findByIdAndTenantId(Long id, Long tenantId);

    Page<ReportJob> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
}
