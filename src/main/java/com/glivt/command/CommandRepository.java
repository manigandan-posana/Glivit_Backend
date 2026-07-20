package com.glivt.command;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);

    Page<DeviceCommand> findByTenantIdOrderByRequestedAtDesc(Long tenantId, Pageable pageable);
}
