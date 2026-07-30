package com.glivt.tenant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantUserRepository extends JpaRepository<TenantUser, Long> {

    List<TenantUser> findByUserId(Long userId);

    Optional<TenantUser> findByUserIdAndTenantId(Long userId, Long tenantId);

    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);

    long countByTenantId(Long tenantId);

    @Query("select tu.tenantId from TenantUser tu where tu.userId = :userId")
    List<Long> tenantIdsForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from TenantUser tu where tu.tenantId = :tenantId")
    void deleteByTenantId(@Param("tenantId") Long tenantId);
}
