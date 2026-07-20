package com.glivt.dashboard;

import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final DeviceCurrentPositionRepository currentPositionRepository;

    public DashboardService(DeviceCurrentPositionRepository currentPositionRepository) {
        this.currentPositionRepository = currentPositionRepository;
    }

    /** Aggregates current-position state counts for the tenant (single grouped query). */
    @Transactional(readOnly = true)
    public DashboardSummary summary(Long tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DeviceState state : DeviceState.values()) {
            counts.put(state.name(), 0L);
        }
        long total = 0;
        for (var row : currentPositionRepository.countByStateForTenant(tenantId)) {
            counts.put(row.getState().name(), row.getTotal());
            total += row.getTotal();
        }
        return new DashboardSummary(counts, total, Instant.now());
    }
}
