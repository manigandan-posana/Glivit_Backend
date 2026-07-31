package com.glivt.user;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.DuplicateResourceException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.driver.Driver;
import com.glivt.driver.DriverRepository;
import com.glivt.tenant.TenantUser;
import com.glivt.tenant.TenantUserRepository;
import com.glivt.user.dto.UserDto;
import com.glivt.user.dto.UserUpsertRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class UserService {

    private final UserRepository repository;
    private final DriverRepository driverRepository;
    private final TenantUserRepository tenantUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public UserService(UserRepository repository, DriverRepository driverRepository,
            TenantUserRepository tenantUserRepository,
            PasswordEncoder passwordEncoder, AuditService auditService) {
        this.repository = repository;
        this.driverRepository = driverRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDto> list(Long tenantId, String search, Pageable pageable) {
        String term = search == null || search.isBlank() ? null : search.trim();
        return PageResponse.from(repository.search(tenantId, term, pageable), UserDto::from);
    }

    @Transactional
    public UserDto create(Long tenantId, Long actorId, String actorUsername, UserUpsertRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            throw new BadRequestException("Password is required");
        }
        if (repository.existsByTenantIdAndUsernameIgnoreCase(tenantId, request.username())) {
            throw new DuplicateResourceException("Username already exists");
        }
        validateManager(tenantId, request.managerId());
        User user = new User();
        user.setTenantId(tenantId);
        apply(user, request);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user = repository.save(user);
        // Home-tenant access grant. Without it the user could still sign in (the
        // home tenant needs no grant) but would have no entry in the tenant access
        // map, so the Manage Tenants list would be empty for them.
        if (!tenantUserRepository.existsByUserIdAndTenantId(user.getId(), tenantId)) {
            tenantUserRepository.save(TenantUser.grant(tenantId, user.getId(), true, actorId));
        }
        syncDriverRecord(user);
        auditService.record(tenantId, actorId, actorUsername, "CREATE_USER", "USER",
                String.valueOf(user.getId()), "SUCCESS",
                user.getRole() == Role.DRIVER ? "Driver account (driver record linked)" : null);
        return UserDto.from(user);
    }

    @Transactional
    public UserDto update(Long tenantId, Long actorId, String actorUsername, Long id,
            UserUpsertRequest request) {
        User user = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (repository.existsByTenantIdAndUsernameIgnoreCaseAndIdNot(
                tenantId, request.username(), id)) {
            throw new DuplicateResourceException("Username already exists");
        }
        validateManager(tenantId, request.managerId());
        apply(user, request);
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        user = repository.save(user);
        syncDriverRecord(user);
        auditService.record(tenantId, actorId, actorUsername, "UPDATE_USER", "USER",
                String.valueOf(user.getId()), "SUCCESS", "Profile or permission change");
        return UserDto.from(user);
    }

    @Transactional
    public void disable(Long tenantId, Long actorId, String actorUsername, Long id) {
        User user = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (actorId.equals(id)) {
            throw new BadRequestException("You cannot disable your own account");
        }
        user.setStatus(UserStatus.DISABLED);
        repository.save(user);
        auditService.record(tenantId, actorId, actorUsername, "DELETE_USER", "USER",
                String.valueOf(id), "SUCCESS", "Soft disabled");
    }

    private void apply(User user, UserUpsertRequest request) {
        user.setUsername(request.username().trim());
        user.setName(request.name().trim());
        user.setEmail(blankToNull(request.email()));
        user.setMobile(blankToNull(request.mobile()));
        user.setAddress(blankToNull(request.address()));
        user.setRole(request.role());
        user.setManagerId(request.managerId());
        user.setStatus(request.status() == null ? UserStatus.ACTIVE : request.status());
        user.setAccountExpiry(request.accountExpiry());
        user.setPermissions(toJson(request.permissions()));
    }

    /**
     * Keeps the driver record for a login in step with the user.
     *
     * Driver management lives entirely in the Users module: saving a user with
     * role DRIVER creates (or refreshes) the linked driver record that vehicle
     * assignment, driver scoring and {@code FleetAccessPolicy} read from. Demoting
     * a user out of the DRIVER role deactivates the record rather than deleting
     * it, so historical trips and scores keep resolving to a driver.
     */
    private void syncDriverRecord(User user) {
        Driver existing = driverRepository
                .findFirstByTenantIdAndUserId(user.getTenantId(), user.getId())
                .orElse(null);

        if (user.getRole() != Role.DRIVER) {
            if (existing != null && existing.isActive()) {
                existing.setActive(false);
                driverRepository.save(existing);
            }
            return;
        }

        Driver driver = existing == null ? new Driver() : existing;
        driver.setTenantId(user.getTenantId());
        driver.setUserId(user.getId());
        driver.setName(user.getName());
        driver.setPhone(user.getMobile());
        if (driver.getIdentifier() == null) {
            // drivers.identifier is VARCHAR(64) while users.username is VARCHAR(120).
            String username = user.getUsername();
            driver.setIdentifier(username.length() <= 64 ? username : username.substring(0, 64));
        }
        driver.setActive(user.getStatus() == UserStatus.ACTIVE);
        driverRepository.save(driver);
    }

    private void validateManager(Long tenantId, Long managerId) {
        if (managerId != null && repository.findByIdAndTenantId(managerId, tenantId).isEmpty()) {
            throw new BadRequestException("Manager is not available for this tenant");
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BadRequestException("Permissions could not be saved");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
