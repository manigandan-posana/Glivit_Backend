package com.glivt.ai.scheduler;

import com.glivt.ai.entity.ScheduledJobLock;
import com.glivt.ai.repository.ScheduledJobLockRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed lease so a scheduled job runs exactly once across all backend
 * instances.
 *
 * <p>Without this, two instances would both generate driver scores or geofence
 * suggestions for every tenant on the same night. The lease has an expiry, so a
 * crashed holder never blocks the job permanently.
 */
@Service
public class JobLockService {

    private static final Logger log = LoggerFactory.getLogger(JobLockService.class);

    /** Identifies this JVM in the lock row, purely for diagnosis. */
    private static final String INSTANCE_ID = resolveInstanceId();

    private final ScheduledJobLockRepository repository;

    public JobLockService(ScheduledJobLockRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs {@code task} only if this instance wins the lease.
     *
     * @return true when the task ran here
     */
    public boolean runIfLeader(String jobName, Duration lease, Runnable task) {
        if (!acquire(jobName, lease)) {
            log.debug("job.skipped name={} reason=lock-held-elsewhere", jobName);
            return false;
        }
        long started = System.currentTimeMillis();
        try {
            task.run();
            log.info("job.completed name={} durationMs={}", jobName,
                    System.currentTimeMillis() - started);
            return true;
        } catch (Exception ex) {
            // A failing job must not leave the lease behind or kill the scheduler.
            log.error("job.failed name={} durationMs={} error={}", jobName,
                    System.currentTimeMillis() - started, ex.toString(), ex);
            return true;
        } finally {
            release(jobName);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquire(String jobName, Duration lease) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(lease);
        try {
            Optional<ScheduledJobLock> existing = repository.findById(jobName);
            if (existing.isPresent()) {
                ScheduledJobLock lock = existing.get();
                if (lock.getExpiresAt() != null && lock.getExpiresAt().isAfter(now)) {
                    return false;
                }
                // Expired lease: take it over.
                lock.setLockedBy(INSTANCE_ID);
                lock.setLockedAt(now);
                lock.setExpiresAt(expiresAt);
                repository.saveAndFlush(lock);
                return true;
            }
            ScheduledJobLock lock = new ScheduledJobLock();
            lock.setJobName(jobName);
            lock.setLockedBy(INSTANCE_ID);
            lock.setLockedAt(now);
            lock.setExpiresAt(expiresAt);
            repository.saveAndFlush(lock);
            return true;
        } catch (DataIntegrityViolationException race) {
            // Another instance inserted the same row first.
            return false;
        } catch (Exception ex) {
            log.warn("Could not acquire job lock {}: {}", jobName, ex.getMessage());
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String jobName) {
        try {
            repository.findById(jobName)
                    .filter(lock -> INSTANCE_ID.equals(lock.getLockedBy()))
                    .ifPresent(repository::delete);
        } catch (Exception ex) {
            log.debug("Could not release job lock {}: {}", jobName, ex.getMessage());
        }
    }

    private static String resolveInstanceId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown-host";
        }
        return host + ":" + ProcessHandle.current().pid() + ":"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
