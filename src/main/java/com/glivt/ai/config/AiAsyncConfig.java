package com.glivt.ai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded, isolated thread pool for AI work (a bulkhead).
 *
 * <p>The rejection policy is deliberately NOT {@code CallerRunsPolicy}. Caller-runs
 * pushes AI work back onto the thread that produced it - during a GPS burst that
 * is the ingestion thread, so a slow or dead AI service would directly stall GPS
 * ingestion, which is exactly what must never happen. Instead a saturated queue
 * drops the task and records a metric; the durable outbox means nothing is
 * actually lost, and the next poll picks the work up again.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AiAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AiAsyncConfig.class);

    public static final String AI_EXECUTOR = "aiTaskExecutor";

    /** Count of AI tasks shed because the pool was saturated. Surfaced in diagnostics. */
    public static final AtomicLong REJECTED_TASKS = new AtomicLong();

    @Bean(name = AI_EXECUTOR)
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ai-async-");
        executor.setRejectedExecutionHandler(new DropAndCountPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * Sheds load instead of blocking the producer. GPS ingestion must never wait
     * for AI, so a full queue means "skip this evaluation", not "run it here".
     */
    static final class DropAndCountPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            long total = REJECTED_TASKS.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                log.warn("AI executor saturated; deferred {} AI evaluation(s) so far. "
                        + "Queued work remains in the outbox and is retried.", total);
            }
        }
    }
}
