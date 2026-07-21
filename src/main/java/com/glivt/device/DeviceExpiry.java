package com.glivt.device;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Effective device-expiry helper.
 *
 * <p>A device is expired when its stored status is {@code EXPIRED} <em>or</em>
 * its expiry date has passed in the device's own timezone - even if an operator
 * never flipped the status manually. Centralised so the device list, dashboard
 * counts and (future) AI tools all agree on what "expired" means.
 */
public final class DeviceExpiry {

    private DeviceExpiry() {
    }

    public static boolean isExpired(DeviceStatus status, LocalDate expiryDate, String timezone) {
        if (status == DeviceStatus.EXPIRED) {
            return true;
        }
        if (expiryDate == null) {
            return false;
        }
        return expiryDate.isBefore(today(timezone));
    }

    public static boolean isExpired(Device device) {
        return isExpired(device.getStatus(), device.getExpiryDate(), device.getTimezone());
    }

    private static LocalDate today(String timezone) {
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank()
                    ? ZoneId.of("Asia/Kolkata")
                    : ZoneId.of(timezone);
        } catch (DateTimeException ex) {
            zone = ZoneId.of("Asia/Kolkata");
        }
        return LocalDate.now(zone);
    }
}
