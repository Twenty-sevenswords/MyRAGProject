package com.yizhaoqi.smartpai.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置
 *
 * 暴露的核心指标（Prometheus 格式，路径 /actuator/prometheus）：
 *
 * | 指标名                          | 类型    | 说明                        |
 * |---------------------------------|---------|-----------------------------|
 * | rag_query_total                 | Counter | RAG 查询总次数              |
 * | rag_cache_hit_total             | Counter | 缓存命中次数                |
 * | rag_retrieval_duration_seconds  | Timer   | 检索耗时                    |
 * | rag_generation_duration_seconds | Timer   | LLM 生成耗时                |
 * | agent_pipeline_duration_seconds | Timer   | Agent 完整流水线耗时        |
 * | hallucination_detected_total    | Counter | 幻觉检测触发次数            |
 * | grading_insufficient_total      | Counter | 文档不足触发重检索次数      |
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public Counter ragQueryCounter(MeterRegistry registry) {
        return Counter.builder("rag.query.total")
                .description("Total number of RAG queries")
                .register(registry);
    }

    @Bean
    public Counter ragCacheHitCounter(MeterRegistry registry) {
        return Counter.builder("rag.cache.hit.total")
                .description("Number of cache hits in memory node")
                .register(registry);
    }

    @Bean
    public Counter hallucinationDetectedCounter(MeterRegistry registry) {
        return Counter.builder("hallucination.detected.total")
                .description("Number of times hallucination was detected")
                .register(registry);
    }

    @Bean
    public Counter gradingInsufficientCounter(MeterRegistry registry) {
        return Counter.builder("grading.insufficient.total")
                .description("Number of times grading found insufficient context")
                .register(registry);
    }

    @Bean
    public Timer ragRetrievalTimer(MeterRegistry registry) {
        return Timer.builder("rag.retrieval.duration")
                .description("Time spent on hybrid search retrieval")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer ragGenerationTimer(MeterRegistry registry) {
        return Timer.builder("rag.generation.duration")
                .description("Time spent on LLM generation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer agentPipelineTimer(MeterRegistry registry) {
        return Timer.builder("agent.pipeline.duration")
                .description("Total time for the full agent pipeline")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
