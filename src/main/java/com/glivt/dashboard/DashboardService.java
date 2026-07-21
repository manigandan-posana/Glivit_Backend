package com.glivt.dashboard;

import com.glivt.device.DeviceExpiry;
import com.glivt.device.DeviceRepository;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final DeviceRepository deviceRepository;

    public DashboardService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Device-first fleet status counts. Every device is counted - including
     * devices that have never reported (counted as {@code NO_DATA}) and devices
     * whose expiry date has passed (counted as {@code EXPIRED}) even if the
     * stored status was never manually changed. The mobile client never has to
     * reconcile "devices" vs "positions" totals.
     */
    @Transactional(readOnly = true)
    public DashboardSummary summary(Long tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DeviceState state : DeviceState.values()) {
            counts.put(state.name(), 0L);
        }

        long total = 0;
        for (var row : deviceRepository.currentStateRows(tenantId)) {
            DeviceState effective = effectiveState(row);
            counts.merge(effective.name(), 1L, Long::sum);
            total++;
        }
        return new DashboardSummary(counts, total, Instant.now());
    }

    private DeviceState effectiveState(DeviceRepository.DeviceStateRow row) {
        if (row.getStatus() == DeviceStatus.SUSPENDED) {
            return DeviceState.SUSPENDED;
        }
        if (DeviceExpiry.isExpired(row.getStatus(), row.getExpiryDate(), row.getTimezone())) {
            return DeviceState.EXPIRED;
        }
        return row.getCurrentState() != null ? row.getCurrentState() : DeviceState.NO_DATA;
    }
}
