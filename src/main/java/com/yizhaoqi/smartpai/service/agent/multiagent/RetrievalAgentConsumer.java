package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 并行检索 Agent 消费者
 *
 * 同时监听向量检索和关键词检索两个 Topic，
 * 使用线程池并发执行，互不阻塞。
 *
 * 执行完成后：
 * 1. 将结果发布到 agent.retrieval.results Topic
 * 2. AgentStateManager（Redisson锁）原子写回 Redis 状态树
 * 3. 若所有子任务完成，触发反思评估流程
 */
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class RetrievalAgentConsumer {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAgentConsumer.class);

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private AgentStateManager stateManager;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("mcpTaskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @Autowired(required = false)
    private Counter ragQueryCounter;

    // ── 向量语义检索 ──────────────────────────────────────────────────────────

    @KafkaListener(
            topics = MultiAgentKafkaOrchestrator.TOPIC_VECTOR_RETRIEVAL,
            groupId = "agent-vector-retrieval-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeVectorRetrieval(String message) {
        taskExecutor.execute(() -> {
            try {
                AgentTask task = objectMapper.readValue(message, AgentTask.class);
                log.info("[RetrievalAgent] 向量检索开始: taskId={}, correlationId={}",
                        task.getTaskId(), task.getCorrelationId());

                long start = System.currentTimeMillis();
                List<SearchResult> results = hybridSearchService.searchWithPermission(
                        task.getQuestion(), task.getUserId(), 5);

                AgentTaskResult result = AgentTaskResult.builder()
                        .taskId(task.getTaskId())
                        .correlationId(task.getCorrelationId())
                        .taskType(AgentTask.TaskType.VECTOR_RETRIEVAL)
                        .success(true)
                        .searchResults(results)
                        .durationMs(System.currentTimeMillis() - start)
                        .completedAt(LocalDateTime.now())
                        .build();

                publishResultAndCheckCompletion(result, task);
                if (ragQueryCounter != null) ragQueryCounter.increment();

            } catch (Exception e) {
                log.error("[RetrievalAgent] 向量检索失败: {}", e.getMessage(), e);
                handleFailure(message, AgentTask.TaskType.VECTOR_RETRIEVAL, e);
            }
        });
    }

    // ── 关键词全文检索 ────────────────────────────────────────────────────────

    @KafkaListener(
            topics = MultiAgentKafkaOrchestrator.TOPIC_KEYWORD_RETRIEVAL,
            groupId = "agent-keyword-retrieval-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeKeywordRetrieval(String message) {
        taskExecutor.execute(() -> {
            try {
                AgentTask task = objectMapper.readValue(message, AgentTask.class);
                log.info("[RetrievalAgent] 关键词检索开始: taskId={}, correlationId={}",
                        task.getTaskId(), task.getCorrelationId());

                long start = System.currentTimeMillis();
                // 关键词检索：用提取的关键词拼接查询
                String keywordQuery = String.join(" ", task.getKeywords());
                List<SearchResult> results = hybridSearchService.searchWithPermission(
                        keywordQuery, task.getUserId(), 5);

                AgentTaskResult result = AgentTaskResult.builder()
                        .taskId(task.getTaskId())
                        .correlationId(task.getCorrelationId())
                        .taskType(AgentTask.TaskType.KEYWORD_RETRIEVAL)
                        .success(true)
                        .searchResults(results)
                        .durationMs(System.currentTimeMillis() - start)
                        .completedAt(LocalDateTime.now())
                        .build();

                publishResultAndCheckCompletion(result, task);

            } catch (Exception e) {
                log.error("[RetrievalAgent] 关键词检索失败: {}", e.getMessage(), e);
                handleFailure(message, AgentTask.TaskType.KEYWORD_RETRIEVAL, e);
            }
        });
    }

    /**
     * 写回结果并检查是否触发下一阶段
     *
     * 关键点：appendResult 内部使用 Redisson 分布式锁，
     * 保证并发写回时 completedCount 的原子性。
     * 返回 true 表示所有检索子任务完成，触发反思评估。
     */
    private void publishResultAndCheckCompletion(AgentTaskResult result, AgentTask task) {
        // 写回 Redis 状态树（Redisson 锁保护）
        boolean allDone = stateManager.appendResult(result.getCorrelationId(), result);

        log.info("[RetrievalAgent] 结果写回完成: correlationId={}, taskType={}, allDone={}",
                result.getCorrelationId(), result.getTaskType(), allDone);

        if (allDone) {
            // 所有检索子任务完成，触发反思评估
            triggerReflection(task);
        }
    }

    /**
     * 触发反思评估阶段
     */
    private void triggerReflection(AgentTask originalTask) {
        log.info("[RetrievalAgent] 触发反思评估: correlationId={}", originalTask.getCorrelationId());

        AgentTask reflectionTask = AgentTask.builder()
                .taskId(UUID.randomUUID().toString())
                .correlationId(originalTask.getCorrelationId())
                .type(AgentTask.TaskType.REFLECTION)
                .question(originalTask.getQuestion())
                .userId(originalTask.getUserId())
                .sessionId(originalTask.getSessionId())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            kafkaTemplate.send(MultiAgentKafkaOrchestrator.TOPIC_REFLECTION_TASKS,
                    originalTask.getCorrelationId(), objectMapper.writeValueAsString(reflectionTask));
        } catch (Exception e) {
            log.error("[RetrievalAgent] 触发反思评估失败: {}", e.getMessage(), e);
        }
    }

    private void handleFailure(String message, AgentTask.TaskType type, Exception e) {
        try {
            AgentTask task = objectMapper.readValue(message, AgentTask.class);
            AgentTaskResult failResult = AgentTaskResult.builder()
                    .taskId(task.getTaskId())
                    .correlationId(task.getCorrelationId())
                    .taskType(type)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .completedAt(LocalDateTime.now())
                    .build();
            // 失败也要写回，保证 completedCount 正确递增
            stateManager.appendResult(task.getCorrelationId(), failResult);
        } catch (Exception ex) {
            log.error("[RetrievalAgent] 处理失败结果时异常: {}", ex.getMessage());
        }
    }
}
