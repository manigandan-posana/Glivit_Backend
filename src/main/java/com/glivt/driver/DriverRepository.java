package com.glivt.driver;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findByTenantId(Long tenantId);

    Optional<Driver> findByIdAndTenantId(Long id, Long tenantId);

    /** Driver record ids linked to a user login (usually one, but modelled as many). */
    @Query("select d.id from Driver d where d.tenantId = :tenantId and d.userId = :userId")
    List<Long> driverIdsForUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
