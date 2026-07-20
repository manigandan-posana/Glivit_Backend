package com.glivt.driver;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findByTenantId(Long tenantId);

    Optional<Driver> findByIdAndTenantId(Long id, Long tenantId);
}
