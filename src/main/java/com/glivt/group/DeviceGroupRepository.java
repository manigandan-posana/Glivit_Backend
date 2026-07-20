package com.glivt.group;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, Long> {

    List<DeviceGroup> findByTenantId(Long tenantId);

    Optional<DeviceGroup> findByIdAndTenantId(Long id, Long tenantId);

    long countByTenantIdAndParentId(Long tenantId, Long parentId);
}
