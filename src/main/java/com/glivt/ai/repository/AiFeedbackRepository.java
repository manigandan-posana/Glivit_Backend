package com.glivt.ai.repository;

import com.glivt.ai.entity.AiFeedback;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiFeedbackRepository extends JpaRepository<AiFeedback, Long> {

    List<AiFeedback> findByTenantId(Long tenantId);

    List<AiFeedback> findByTenantIdAndAiEventId(Long tenantId, Long aiEventId);
}
