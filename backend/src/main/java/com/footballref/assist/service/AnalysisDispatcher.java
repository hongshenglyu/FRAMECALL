package com.footballref.assist.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AnalysisDispatcher {
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public AnalysisDispatcher(@Value("${app.analysis.workers:2}") int workers,
                              @Value("${app.analysis.queue-capacity:16}") int capacity) {
        if (workers < 1 || workers > 32 || capacity < 0 || capacity > 1024) {
            throw new IllegalArgumentException("Invalid analysis executor limits");
        }
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("analysis-worker-");
        executor.setDaemon(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
    }

    public void submit(Runnable action) { executor.execute(action); }
    public int queued() { return executor.getThreadPoolExecutor().getQueue().size(); }
    @PreDestroy public void close() { executor.shutdown(); }
}
