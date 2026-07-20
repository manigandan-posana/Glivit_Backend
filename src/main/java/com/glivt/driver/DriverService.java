package com.glivt.driver;

import com.glivt.audit.AuditService;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.driver.dto.DriverDto;
import com.glivt.driver.dto.DriverRequest;
import com.glivt.project.ProjectRepository;
import com.glivt.vehicle.VehicleRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverService {

    private final DriverRepository repository;
    private final ProjectRepository projectRepository;
    private final VehicleRepository vehicleRepository;
    private final AuditService auditService;

    public DriverService(DriverRepository repository, ProjectRepository projectRepository,
                         VehicleRepository vehicleRepository, AuditService auditService) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.vehicleRepository = vehicleRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DriverDto> list(Long tenantId) {
        return repository.findByTenantId(tenantId).stream().map(DriverDto::from).toList();
    }

    @Transactional
    public DriverDto create(Long tenantId, Long userId, String username, DriverRequest request) {
        validateProject(tenantId, request.projectId());
        Driver driver = new Driver();
        driver.setTenantId(tenantId);
        apply(driver, request);
        driver = repository.save(driver);
        auditService.record(tenantId, userId, username, "CREATE_DRIVER", "DRIVER",
                String.valueOf(driver.getId()), "SUCCESS", null);
        return DriverDto.from(driver);
    }

    @Transactional
    public DriverDto update(Long tenantId, Long userId, String username, Long id, DriverRequest request) {
        validateProject(tenantId, request.projectId());
        Driver driver = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
        apply(driver, request);
        driver = repository.save(driver);
        auditService.record(tenantId, userId, username, "UPDATE_DRIVER", "DRIVER",
                String.valueOf(driver.getId()), "SUCCESS", null);
        return DriverDto.from(driver);
    }

    @Transactional
    public void disable(Long tenantId, Long userId, String username, Long id) {
        Driver driver = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found"));
        driver.setActive(false);
        repository.save(driver);
        auditService.record(tenantId, userId, username, "DELETE_DRIVER", "DRIVER",
                String.valueOf(driver.getId()), "SUCCESS",
                "Soft disabled; assigned vehicles=" + vehicleRepository.countByTenantIdAndDriverId(tenantId, id));
    }

    private void validateProject(Long tenantId, Long projectId) {
        if (projectId != null && projectRepository.findByIdAndTenantId(projectId, tenantId).isEmpty()) {
            throw new BadRequestException("Project is not available for this tenant");
        }
    }

    private void apply(Driver driver, DriverRequest request) {
        driver.setName(request.name().trim());
        driver.setIdentifier(blankToNull(request.identifier()));
        driver.setPhone(blankToNull(request.phone()));
        driver.setLicenceNumber(blankToNull(request.licenceNumber()));
        driver.setLicenceExpiry(request.licenceExpiry());
        driver.setProjectId(request.projectId());
        driver.setEmergencyContact(blankToNull(request.emergencyContact()));
        driver.setActive(request.active() == null || request.active());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
