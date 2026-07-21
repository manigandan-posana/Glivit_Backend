package com.glivt.access;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProjectAssignmentRepository extends JpaRepository<UserProjectAssignment, Long> {

    @Query("select a.projectId from UserProjectAssignment a where a.tenantId = :tenantId and a.userId = :userId")
    List<Long> projectIds(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
