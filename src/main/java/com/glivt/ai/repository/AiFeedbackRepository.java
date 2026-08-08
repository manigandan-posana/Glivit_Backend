package com.glivt.ai.repository;

import com.glivt.ai.entity.AiFeedback;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    List<AiFeedback> findByTenantId(Long tenantId);

    List<AiFeedback> findByTenantIdAndAiEventId(Long tenantId, Long aiEventId);

    /** Used only to build human-reviewed evaluation reports, never to retrain. */
    long countByTenantIdAndCorrectTrue(Long tenantId);

    long countByTenantIdAndCorrectFalse(Long tenantId);
}
