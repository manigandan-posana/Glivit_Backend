package com.glivt.ai.repository;

import com.glivt.ai.entity.TenantSpeedPolicy;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSpeedPolicyRepository extends JpaRepository<TenantSpeedPolicy, Long> {

    List<TenantSpeedPolicy> findByTenantId(Long tenantId);
}
