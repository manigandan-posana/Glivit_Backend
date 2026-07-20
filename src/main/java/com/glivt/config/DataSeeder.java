package com.glivt.config;

import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.device.DeviceStatus;
import com.glivt.position.DeviceCurrentPosition;
import com.glivt.position.DeviceCurrentPositionRepository;
import com.glivt.position.DeviceState;
import com.glivt.tenant.Tenant;
import com.glivt.tenant.TenantRepository;
import com.glivt.user.Role;
import com.glivt.user.User;
import com.glivt.user.UserRepository;
import com.glivt.vehicle.Vehicle;
import com.glivt.vehicle.VehicleCategory;
import com.glivt.vehicle.VehicleRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent demo/simulation seed. Only runs when app.seed-demo=true so demo
 * data never mixes with production. Creates one clearly-marked demo tenant with
 * a Super Admin / Admin / Driver and a small fleet in mixed states.
 */
@Component
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEMO_CODE = "DEMO";
    private static final String DEMO_PASSWORD = "Admin@12345";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceCurrentPositionRepository currentPositionRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(TenantRepository tenantRepository, UserRepository userRepository,
                      VehicleRepository vehicleRepository, DeviceRepository deviceRepository,
                      DeviceCurrentPositionRepository currentPositionRepository,
                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.currentPositionRepository = currentPositionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (tenantRepository.findByCompanyCodeIgnoreCase(DEMO_CODE).isPresent()) {
            log.info("Demo tenant already present; skipping seed.");
            return;
        }
        log.info("Seeding DEMO tenant and fleet (app.seed-demo=true).");

        Tenant tenant = new Tenant();
        tenant.setCompanyCode(DEMO_CODE);
        tenant.setName("Glivt Demo Fleet");
        tenant.setAppName("Glivt Demo");
        tenant.setPrimaryColor("#27D34D");
        tenant.setSecondaryColor("#2A91BD");
        tenant.setSupportPhone("+910000000000");
        tenant.setSupportEmail("support@example.com");
        tenant.setEnabledModules("dashboard,map,reports,geofences,notifications");
        tenant.setMaxHistoryDays(90);
        tenant = tenantRepository.save(tenant);

        createUser(tenant.getId(), "superadmin", "Demo Super Admin", Role.SUPER_ADMIN);
        User admin = createUser(tenant.getId(), "admin", "Demo Admin", Role.ADMIN);
        createUser(tenant.getId(), "driver", "Demo Driver", Role.DRIVER);

        seedVehicle(tenant.getId(), admin.getId(), "TN20CM7677", VehicleCategory.CAR,
                DeviceState.RUNNING, 12.9718, 77.5946, 46, "864000000000001");
        seedVehicle(tenant.getId(), admin.getId(), "KA05MJ1234", VehicleCategory.TRUCK,
                DeviceState.STOPPED, 12.9352, 77.6245, 0, "864000000000002");
        seedVehicle(tenant.getId(), admin.getId(), "KA01AB9999", VehicleCategory.BUS,
                DeviceState.IDLE, 12.9611, 77.6387, 3, "864000000000003");
        seedVehicle(tenant.getId(), admin.getId(), "TN09XY4321", VehicleCategory.BIKE,
                DeviceState.NO_DATA, 13.0102, 77.5590, 0, "864000000000004");

        log.info("Demo seed complete. Company code DEMO / user superadmin|admin|driver / pw {}",
                DEMO_PASSWORD);
    }

    private User createUser(Long tenantId, String username, String name, Role role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setName(name);
        user.setEmail(username + "@example.com");
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        return userRepository.save(user);
    }

    private void seedVehicle(Long tenantId, Long managerId, String registration,
                             VehicleCategory category, DeviceState state,
                             double lat, double lng, double speed, String imei) {
        Vehicle vehicle = new Vehicle();
        vehicle.setTenantId(tenantId);
        vehicle.setName(registration);
        vehicle.setRegistrationNumber(registration);
        vehicle.setCategory(category);
        vehicle = vehicleRepository.save(vehicle);

        Device device = new Device();
        device.setTenantId(tenantId);
        device.setVehicleId(vehicle.getId());
        device.setManagerId(managerId);
        device.setImei(imei);
        device.setName(registration);
        device.setModel("DEMO-GT06");
        device.setExpiryDate(LocalDate.now().plusMonths(6));
        device.setActivatedAt(LocalDate.now().minusMonths(6));
        device.setStatus(DeviceStatus.ACTIVE);
        device = deviceRepository.save(device);

        DeviceCurrentPosition position = new DeviceCurrentPosition();
        position.setDeviceId(device.getId());
        position.setTenantId(tenantId);
        position.setVehicleId(vehicle.getId());
        position.setLatitude(lat);
        position.setLongitude(lng);
        position.setSpeed(speed);
        position.setCourse(90);
        position.setIgnition(state == DeviceState.RUNNING || state == DeviceState.IDLE);
        position.setGpsValid(state != DeviceState.NO_DATA);
        position.setState(state);
        position.setAddress("Bengaluru, Karnataka");
        position.setDeviceTime(Instant.now());
        position.setServerTime(Instant.now());
        position.setUpdatedAt(Instant.now());
        currentPositionRepository.save(position);
    }
}
