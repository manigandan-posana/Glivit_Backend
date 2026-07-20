package com.glivt.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByTenantId(Long tenantId);

    Optional<Project> findByIdAndTenantId(Long id, Long tenantId);
}
