package com.glivt.device;

import com.glivt.position.DeviceState;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByIdAndTenantId(Long id, Long tenantId);

    /** Ingestion lookup: devices authenticate by IMEI, then the token is verified. */
    Optional<Device> findByImei(String imei);

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
              and (:includeSuspended = true or d.status <> com.glivt.device.DeviceStatus.SUSPENDED)
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
                        @Param("includeSuspended") boolean includeSuspended,
                        Pageable pageable);

    long countByTenantIdAndExpiryDateBefore(Long tenantId, LocalDate date);

    // --- FleetAccessPolicy scoping helpers ---

    @Query("select d.id from Device d where d.tenantId = :tenantId and d.vehicleId in :vehicleIds")
    List<Long> deviceIdsByVehicleIds(@Param("tenantId") Long tenantId,
                                     @Param("vehicleIds") java.util.Collection<Long> vehicleIds);

    @Query("select d.id from Device d where d.tenantId = :tenantId and d.projectId in :projectIds")
    List<Long> deviceIdsByProjectIds(@Param("tenantId") Long tenantId,
                                     @Param("projectIds") java.util.Collection<Long> projectIds);

    @Query("""
            select d from Device d
            where d.tenantId = :tenantId
              and d.id in :deviceIds
              and (:includeSuspended = true or d.status <> com.glivt.device.DeviceStatus.SUSPENDED)
              and (:projectId is null or d.projectId = :projectId)
              and (:groupId is null or d.groupId = :groupId)
              and (:search is null
                   or lower(d.name) like lower(concat('%', :search, '%'))
                   or lower(d.imei) like lower(concat('%', :search, '%')))
            """)
    Page<Device> searchScoped(@Param("tenantId") Long tenantId,
                              @Param("projectId") Long projectId,
                              @Param("groupId") Long groupId,
                              @Param("search") String search,
                              @Param("includeSuspended") boolean includeSuspended,
                              @Param("deviceIds") java.util.Collection<Long> deviceIds,
                              Pageable pageable);

    /** Projection for device-first dashboard counting (every device, even NO_DATA). */
    interface DeviceStateRow {
        DeviceStatus getStatus();

        LocalDate getExpiryDate();

        String getTimezone();

        DeviceState getCurrentState();
    }

    /**
     * Device-first left join to the current-position table so devices that have
     * never reported are still counted (as NO_DATA) on the dashboard.
     */
    @Query("""
            select d.status as status,
                   d.expiryDate as expiryDate,
                   d.timezone as timezone,
                   cp.state as currentState
            from Device d
            left join DeviceCurrentPosition cp on cp.deviceId = d.id
            where d.tenantId = :tenantId
            """)
    List<DeviceStateRow> currentStateRows(@Param("tenantId") Long tenantId);
}
