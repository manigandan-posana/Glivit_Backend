package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.glivt.ai.security.FleetAccessPolicy;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.driver.Driver;
import com.glivt.driver.DriverRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FleetAccessPolicyTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DriverRepository driverRepository;

    private FleetAccessPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new FleetAccessPolicy(vehicleRepository, deviceRepository, driverRepository);
    }

    @Test
    void tenantCanAccessOwnVehicle() {
        Vehicle v = new Vehicle();
        v.setId(5L);
        v.setTenantId(1L);
        when(vehicleRepository.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(v));
        assertThat(policy.requireVehicle(1L, 5L)).isSameAs(v);
    }

    @Test
    void tenantBCannotAccessTenantAVehicle() {
        // Repository is queried with tenant B's id, so the lookup misses.
        when(vehicleRepository.findByIdAndTenantId(5L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireVehicle(2L, 5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossTenantDeviceIsNotFound() {
        when(deviceRepository.findByIdAndTenantId(9L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireDevice(2L, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossTenantDriverIsNotFound() {
        when(driverRepository.findByIdAndTenantId(7L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireDriver(2L, 7L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> policy.requireVehicle(1L, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void tenantCanAccessOwnDriver() {
        Driver d = new Driver();
        d.setId(7L);
        d.setTenantId(1L);
        when(driverRepository.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(d));
        assertThat(policy.requireDriver(1L, 7L)).isSameAs(d);
    }

    @Test
    void tenantCanAccessOwnDevice() {
        Device dev = new Device();
        dev.setId(9L);
        dev.setTenantId(1L);
        when(deviceRepository.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(dev));
        assertThat(policy.requireDevice(1L, 9L)).isSameAs(dev);
    }
}
