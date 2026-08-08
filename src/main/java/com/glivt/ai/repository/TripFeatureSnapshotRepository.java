package com.glivt.ai.repository;

import com.glivt.ai.entity.TripFeatureSnapshot;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


public interface TripFeatureSnapshotRepository extends JpaRepository<TripFeatureSnapshot, Long> {

    List<TripFeatureSnapshot> findByTenantIdAndVehicleIdOrderByStartTimeDesc(Long tenantId, Long vehicleId);

    Page<TripFeatureSnapshot> findByTenantId(Long tenantId, Pageable pageable);

    List<TripFeatureSnapshot> findByTenantIdAndStartTimeAfter(Long tenantId, java.time.Instant after);

    /** Trip features for one driver over a period - the daily scoring input. */
    @org.springframework.data.jpa.repository.Query("""
            select t from TripFeatureSnapshot t
            where t.tenantId = :tenantId and t.driverId = :driverId
              and t.startTime >= :from and t.startTime < :to
            """)
    List<TripFeatureSnapshot> findForDriverBetween(
            @org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("driverId") Long driverId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** Distinct drivers with trips in a window - drives the daily scoring job. */
    @org.springframework.data.jpa.repository.Query("""
            select distinct t.driverId from TripFeatureSnapshot t
            where t.tenantId = :tenantId and t.driverId is not null
              and t.startTime >= :from and t.startTime < :to
            """)
    List<Long> findDriverIdsWithTrips(
            @org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    boolean existsByTenantIdAndVehicleIdAndStartTime(Long tenantId, Long vehicleId,
            java.time.Instant startTime);
}
