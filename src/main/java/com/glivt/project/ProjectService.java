package com.glivt.project;

import com.glivt.audit.AuditService;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.DeviceRepository;
import com.glivt.project.dto.ProjectDto;
import com.glivt.project.dto.ProjectRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository repository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;

    public ProjectService(ProjectRepository repository, DeviceRepository deviceRepository,
                          AuditService auditService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ProjectDto> list(Long tenantId) {
        return repository.findByTenantId(tenantId).stream().map(ProjectDto::from).toList();
    }

    @Transactional
    public ProjectDto create(Long tenantId, Long userId, String username, ProjectRequest request) {
        Project project = new Project();
        project.setTenantId(tenantId);
        apply(project, request);
        project = repository.save(project);
        auditService.record(tenantId, userId, username, "CREATE_PROJECT", "PROJECT",
                String.valueOf(project.getId()), "SUCCESS", null);
        return ProjectDto.from(project);
    }

    @Transactional
    public ProjectDto update(Long tenantId, Long userId, String username, Long id,
                             ProjectRequest request) {
        Project project = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        apply(project, request);
        project = repository.save(project);
        auditService.record(tenantId, userId, username, "UPDATE_PROJECT", "PROJECT",
                String.valueOf(project.getId()), "SUCCESS", null);
        return ProjectDto.from(project);
    }

    @Transactional
    public void disable(Long tenantId, Long userId, String username, Long id) {
        Project project = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        project.setStatus("INACTIVE");
        repository.save(project);
        auditService.record(tenantId, userId, username, "DELETE_PROJECT", "PROJECT",
                String.valueOf(project.getId()), "SUCCESS",
                "Soft disabled; device count=" + deviceRepository.countByTenantIdAndProjectId(tenantId, id));
    }

    private void apply(Project project, ProjectRequest request) {
        project.setName(request.name().trim());
        project.setDescription(blankToNull(request.description()));
        project.setStatus(request.status() == null || request.status().isBlank()
                ? "ACTIVE" : request.status().trim());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
