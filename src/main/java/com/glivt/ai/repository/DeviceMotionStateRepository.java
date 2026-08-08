package com.glivt.ai.repository;

import com.glivt.ai.entity.DeviceMotionState;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceMotionStateRepository extends JpaRepository<DeviceMotionState, Long> {

    /** Tenant-scoped lookup so a device id from another tenant can never resolve. */
    Optional<DeviceMotionState> findByDeviceIdAndTenantId(Long deviceId, Long tenantId);
}
