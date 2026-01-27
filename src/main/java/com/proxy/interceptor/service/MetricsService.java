package com.proxy.interceptor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class MetricsService {

    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong blockedQueries = new AtomicLong(0);
    private final AtomicLong approvedQueries = new AtomicLong(0);
    private final AtomicLong rejectedQueries = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);

    private final ConcurrentHashMap<String, AtomicLong> queryTypeCount = new ConcurrentHashMap<>();

    public void trackConnection() {
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
    }

    public void trackDisconnection() {
        activeConnections.decrementAndGet();
    }

    public void trackQuery(String type) {
        totalQueries.incrementAndGet();
        queryTypeCount.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void trackBlocked() {
        blockedQueries.incrementAndGet();
    }

    public void trackApproved() {
        approvedQueries.incrementAndGet();
    }

    public void trackRejected() {
        rejectedQueries.incrementAndGet();
    }

    public void trackError() {
        errors.incrementAndGet();
    }

    public Map<String, Object> getMetrics() {
        return Map.of(
                "totalConnections", totalConnections.get(),
                "activeConnections", activeConnections.get(),
                "totalQueries", totalQueries.get(),
                "blockedQueries", blockedQueries.get(),
                "approvedQueries", approvedQueries.get(),
                "rejectedQueries", rejectedQueries.get(),
                "errors", errors.get(),
                "queryTypes", new ConcurrentHashMap<>(queryTypeCount)
        );
    }

    @Scheduled(fixedRate = 60000) // Log metrics every minute
    public void logMetrics() {
        log.info("Metrics: connections={}/{}, queries={}, blocked={}, approved={}, rejected={}",
                activeConnections.get(), totalConnections.get(),
                totalConnections.get(), blockedQueries.get(),
                approvedQueries.get(), rejectedQueries.get());
    }
}
