package com.yizhaoqi.smartpai.langgraph.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer helper for LangGraph execution metrics.
 */
@Component
public class GraphMetrics {

    private final MeterRegistry meterRegistry;

    public GraphMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementGraphRuns(String mode) {
        Counter.builder("langgraph.run.total")
                .tag("mode", mode)
                .register(meterRegistry)
                .increment();
    }

    public void recordNodeDuration(String nodeName, long durationMs) {
        Timer.builder("langgraph.node.duration")
                .tag("node", nodeName)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
