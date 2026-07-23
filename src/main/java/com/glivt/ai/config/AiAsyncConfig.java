package com.glivt.ai.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables asynchronous, post-commit AI processing with a bounded, isolated
 * thread pool (bulkhead). AI work must never starve the request threads that
 * serve GPS ingestion, login and live tracking, so this pool is small and
 * bounded and falls back to caller-runs when saturated.
 */
@Configuration
@EnableAsync
public class AiAsyncConfig {

    public static final String AI_EXECUTOR = "aiTaskExecutor";

    @Bean(name = AI_EXECUTOR)
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-async-");
        // If the queue is full, run on the caller rather than dropping AI work.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
