package com.glivt.device;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Device> findByIngestToken(String ingestToken);

    boolean existsByImei(String imei);

    boolean existsByImeiIgnoreCase(String imei);

    boolean existsByImeiIgnoreCaseAndIdNot(String imei, Long id);

    boolean existsByVehicleIdAndTenantId(Long vehicleId, Long tenantId);

    boolean existsByVehicleIdAndTenantIdAndIdNot(Long vehicleId, Long tenantId, Long id);

    long countByTenantIdAndGroupId(Long tenantId, Long groupId);

    long countByTenantIdAndProjectId(Long tenantId, Long projectId);

    @Query("""
            select d from Device d
            where d.tenantId = :tenantId
              and (:projectId is null or d.projectId = :projectId)
              and (:groupId is null or d.groupId = :groupId)
              and (:search is null
                   or lower(d.name) like lower(concat('%', :search, '%'))
                   or lower(d.imei) like lower(concat('%', :search, '%')))
            """)
    Page<Device> search(@Param("tenantId") Long tenantId,
                        @Param("projectId") Long projectId,
                        @Param("groupId") Long groupId,
                        @Param("search") String search,
                        Pageable pageable);

    long countByTenantIdAndExpiryDateBefore(Long tenantId, LocalDate date);
}
