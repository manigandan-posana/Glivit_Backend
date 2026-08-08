package com.glivt.ai.repository;

import com.glivt.ai.entity.ScheduledJobLock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobLockRepository extends JpaRepository<ScheduledJobLock, String> {
}
