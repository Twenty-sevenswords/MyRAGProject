package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 驱动的多 Agent 协同编排器
 *
 * 架构设计：
 *
 *   用户请求
 *       │
 *       ▼
 *   RouterPlanningAgent（本类）
 *       │  拆解为 N 个子任务，发布到 Kafka
 *       │
 *       ├──► Topic: agent.vector.retrieval  → VectorRetrievalConsumer（线程池并行）
 *       ├──► Topic: agent.keyword.retrieval → KeywordRetrievalConsumer（线程池并行）
 *       │
 *       │  子任务完成后发布结果到 agent.retrieval.results
 *       │  AgentStateManager（Redisson锁）原子写回 Redis 状态树
 *       │
 *       ▼
 *   ReflectionAgent（消费 agent.reflection.tasks）
 *       │  对合并后的检索结果打分，过滤低质量文档
 *       │
 *       ▼
 *   SummaryAgent（消费 agent.summary.tasks）
 *       │  基于高质量文档生成最终回复
 *       │
 *       ▼
 *   最终回复（WebSocket 推送）
 *
 * 与单 Agent 对比：
 * - 单 Agent：向量检索(500ms) + 关键词检索(300ms) + 生成(800ms) = 1600ms
 * - 多 Agent：向量检索 ‖ 关键词检索(max 500ms) + 生成(800ms) = 1300ms，提升约 19%
 *   （实际场景中检索并行化收益更大，尤其是多数据源检索时）
 * - 任务完成率：单 Agent 任一步骤失败则整体失败；多 Agent 部分检索失败仍可汇总其他结果
 */
@Service
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class MultiAgentKafkaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentKafkaOrchestrator.class);

    static final String TOPIC_VECTOR_RETRIEVAL   = "agent.vector.retrieval";
    static final String TOPIC_KEYWORD_RETRIEVAL  = "agent.keyword.retrieval";
    static final String TOPIC_RETRIEVAL_RESULTS  = "agent.retrieval.results";
    static final String TOPIC_REFLECTION_TASKS   = "agent.reflection.tasks";
    static final String TOPIC_SUMMARY_TASKS      = "agent.summary.tasks";

    private static final int TOTAL_RETRIEVAL_TASKS = 2; // 向量 + 关键词
    private static final int PIPELINE_TIMEOUT_SECONDS = 30;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private AgentStateManager stateManager;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 等待汇总结果的 Sink 注册表（correlationId → Sink） */
    private final Map<String, Sinks.One<String>> pendingReplies = new ConcurrentHashMap<>();

    /**
     * 入口：接收用户请求，路由规划并下发子任务
     *
     * @return Flux 流式推送最终回复
     */
    public Flux<String> orchestrate(String question, String userId, String sessionId) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[MultiAgentOrchestrator] 开始多 Agent 协同: correlationId={}, question={}",
                correlationId, question);

        // 初始化 Redis 状态树
        stateManager.initTask(correlationId, TOTAL_RETRIEVAL_TASKS);

        // 注册等待 Sink
        Sinks.One<String> replySink = Sinks.one();
        pendingReplies.put(correlationId, replySink);

        // 路由规划：提取检索关键词
        List<String> keywords = extractKeywords(question);
        log.info("[MultiAgentOrchestrator] 路由规划完成，关键词: {}", keywords);

        // 下发子任务到 Kafka（异步，立即返回）
        publishRetrievalTasks(correlationId, question, keywords, userId, sessionId);

        // 返回 Flux，等待汇总结果
        return replySink.asMono()
                .flux()
                .timeout(Duration.ofSeconds(PIPELINE_TIMEOUT_SECONDS))
                .onErrorResume(TimeoutException.class, e -> {
                    log.error("[MultiAgentOrchestrator] 流水线超时: correlationId={}", correlationId);
                    pendingReplies.remove(correlationId);
                    return Flux.just("系统处理超时，请稍后重试。");
                })
                .doFinally(signal -> pendingReplies.remove(correlationId));
    }

    /**
     * 下发两个并行检索子任务到 Kafka
     */
    private void publishRetrievalTasks(String correlationId, String question,
                                        List<String> keywords, String userId, String sessionId) {
        // 子任务1：向量语义检索
        AgentTask vectorTask = AgentTask.builder()
                .taskId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .type(AgentTask.TaskType.VECTOR_RETRIEVAL)
                .question(question)
                .keywords(keywords)
                .userId(userId)
                .sessionId(sessionId)
                .priority(2)
                .totalSubTasks(TOTAL_RETRIEVAL_TASKS)
                .createdAt(LocalDateTime.now())
                .build();

        // 子任务2：关键词全文检索
        AgentTask keywordTask = AgentTask.builder()
                .taskId(UUID.randomUUID().toString())
                .correlationId(correlationId)
                .type(AgentTask.TaskType.KEYWORD_RETRIEVAL)
                .question(String.join(" ", keywords))
                .keywords(keywords)
                .userId(userId)
                .sessionId(sessionId)
                .priority(1)
                .totalSubTasks(TOTAL_RETRIEVAL_TASKS)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            kafkaTemplate.send(TOPIC_VECTOR_RETRIEVAL, correlationId, serialize(vectorTask));
            kafkaTemplate.send(TOPIC_KEYWORD_RETRIEVAL, correlationId, serialize(keywordTask));
            log.info("[MultiAgentOrchestrator] 子任务已发布到 Kafka: correlationId={}", correlationId);
        } catch (Exception e) {
            log.error("[MultiAgentOrchestrator] 发布子任务失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 由 SummaryAgentConsumer 回调，推送最终回复给等待的 Flux
     */
    public void onSummaryComplete(String correlationId, String reply) {
        Sinks.One<String> sink = pendingReplies.get(correlationId);
        if (sink != null) {
            sink.tryEmitValue(reply);
            log.info("[MultiAgentOrchestrator] 汇总完成，推送回复: correlationId={}", correlationId);
        } else {
            log.warn("[MultiAgentOrchestrator] 未找到等待的 Sink: correlationId={}", correlationId);
        }
    }

    /**
     * 路由规划：用 LLM 提取检索关键词
     */
    private List<String> extractKeywords(String question) {
        try {
            String prompt = "从以下问题中提取3-5个最重要的检索关键词，以逗号分隔，只返回关键词，不要其他内容：\n" + question;
            String response = chatService.chat(prompt);
            String[] parts = response.split("[,，]");
            List<String> keywords = new ArrayList<>();
            for (String part : parts) {
                String kw = part.trim();
                if (!kw.isBlank()) keywords.add(kw);
            }
            return keywords.isEmpty() ? List.of(question) : keywords;
        } catch (Exception e) {
            log.warn("[MultiAgentOrchestrator] 关键词提取失败，使用原始问题: {}", e.getMessage());
            return List.of(question);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
}
