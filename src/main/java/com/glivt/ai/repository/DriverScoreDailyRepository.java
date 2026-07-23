package com.glivt.ai.repository;

import com.glivt.ai.entity.DriverScoreDaily;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DriverScoreDailyRepository extends JpaRepository<DriverScoreDaily, Long> {

    Optional<DriverScoreDaily> findByTenantIdAndDriverIdAndScoreDateAndScorePeriod(
            Long tenantId, Long driverId, LocalDate scoreDate, String scorePeriod);

    List<DriverScoreDaily> findByTenantIdAndDriverIdOrderByScoreDateDesc(Long tenantId, Long driverId);

    List<DriverScoreDaily> findByTenantIdAndScoreDateAndScorePeriodOrderByOverallScoreAsc(
            Long tenantId, LocalDate scoreDate, String scorePeriod);
}
