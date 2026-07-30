package com.glivt.driver;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findByTenantId(Long tenantId);

    long countByTenantId(Long tenantId);

    Optional<Driver> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * The driver record backing a user login. Driver accounts are created through
     * the Users module (role = DRIVER), which keeps exactly one record per login.
     */
    Optional<Driver> findFirstByTenantIdAndUserId(Long tenantId, Long userId);

    /** Driver record ids linked to a user login (usually one, but modelled as many). */
    @Query("select d.id from Driver d where d.tenantId = :tenantId and d.userId = :userId")
    List<Long> driverIdsForUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}