package com.glivt.group;

import com.glivt.audit.AuditService;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.device.DeviceRepository;
import com.glivt.group.dto.GroupDto;
import com.glivt.group.dto.GroupRequest;
import com.glivt.user.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private final DeviceGroupRepository repository;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;

    public GroupService(DeviceGroupRepository repository, UserRepository userRepository,
                        DeviceRepository deviceRepository, AuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<GroupDto> list(Long tenantId) {
        return repository.findByTenantId(tenantId).stream().map(GroupDto::from).toList();
    }

    @Transactional
    public GroupDto create(Long tenantId, Long userId, String username, GroupRequest request) {
        validateReferences(tenantId, request, null);
        DeviceGroup group = new DeviceGroup();
        group.setTenantId(tenantId);
        apply(group, request);
        group = repository.save(group);
        auditService.record(tenantId, userId, username, "CREATE_GROUP", "GROUP",
                String.valueOf(group.getId()), "SUCCESS", null);
        return GroupDto.from(group);
    }

    @Transactional
    public GroupDto update(Long tenantId, Long userId, String username, Long id, GroupRequest request) {
        DeviceGroup group = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        validateReferences(tenantId, request, id);
        apply(group, request);
        group = repository.save(group);
        auditService.record(tenantId, userId, username, "UPDATE_GROUP", "GROUP",
                String.valueOf(group.getId()), "SUCCESS", null);
        return GroupDto.from(group);
    }

    @Transactional
    public void delete(Long tenantId, Long userId, String username, Long id) {
        DeviceGroup group = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        if (repository.countByTenantIdAndParentId(tenantId, id) > 0) {
            throw new BadRequestException("Group has child groups");
        }
        if (deviceRepository.countByTenantIdAndGroupId(tenantId, id) > 0) {
            throw new BadRequestException("Group has assigned devices");
        }
        repository.delete(group);
        auditService.record(tenantId, userId, username, "DELETE_GROUP", "GROUP",
                String.valueOf(id), "SUCCESS", null);
    }

    private void apply(DeviceGroup group, GroupRequest request) {
        group.setName(request.name().trim());
        group.setParentId(request.parentId());
        group.setManagerId(request.managerId());
    }

    private void validateReferences(Long tenantId, GroupRequest request, Long currentGroupId) {
        if (request.parentId() != null) {
            if (request.parentId().equals(currentGroupId)) {
                throw new BadRequestException("Group cannot be its own parent");
            }
            if (repository.findByIdAndTenantId(request.parentId(), tenantId).isEmpty()) {
                throw new BadRequestException("Parent group is not available for this tenant");
            }
        }
        if (request.managerId() != null
                && userRepository.findByIdAndTenantId(request.managerId(), tenantId).isEmpty()) {
            throw new BadRequestException("Manager is not available for this tenant");
        }
    }
}
