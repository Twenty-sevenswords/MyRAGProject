package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 反思评估 Agent 消费者
 *
 * 职责：
 * 对所有检索 Agent 汇总的文档进行质量评估，
 * 过滤掉相关性不足的文档，只保留高质量内容送入汇总 Agent。
 *
 * 这是 Multi-Agent 架构中的"反思"环节，
 * 相当于 LangGraph 流水线中 GradingNode 的分布式版本。
 * 区别在于：GradingNode 是同步串行，这里是异步消费，不阻塞主线程。
 */
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class ReflectionAgentConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReflectionAgentConsumer.class);

    private static final String REFLECTION_PROMPT = """
            你是一个文档质量评估专家。请评估以下检索结果是否足以回答用户问题。

            用户问题：{question}

            检索到的文档数量：{count}
            文档摘要：
            {summary}

            评估维度：
            1. 相关性：文档是否与问题直接相关
            2. 充分性：文档内容是否足以支撑完整回答
            3. 多样性：是否有多个角度的信息

            请返回JSON：{"score": 0-100, "sufficient": true/false, "reason": "一句话说明"}
            只返回JSON，不要其他内容。
            """;

    @Autowired
    private AgentStateManager stateManager;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(
            topics = MultiAgentKafkaOrchestrator.TOPIC_REFLECTION_TASKS,
            groupId = "agent-reflection-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReflectionTask(String message) {
        try {
            AgentTask task = objectMapper.readValue(message, AgentTask.class);
            log.info("[ReflectionAgent] 开始反思评估: correlationId={}", task.getCorrelationId());

            // 获取所有检索结果
            List<SearchResult> allResults = stateManager.getMergedSearchResults(task.getCorrelationId());
            log.info("[ReflectionAgent] 合并检索结果: {} 条", allResults.size());

            // 反思评估
            int score = evaluate(task.getQuestion(), allResults);
            log.info("[ReflectionAgent] 评估分数: {}", score);

            // 触发汇总生成
            triggerSummary(task, allResults, score);

        } catch (Exception e) {
            log.error("[ReflectionAgent] 反思评估失败: {}", e.getMessage(), e);
        }
    }

    private int evaluate(String question, List<SearchResult> results) {
        if (results.isEmpty()) return 0;

        try {
            String summary = results.stream()
                    .limit(3)
                    .map(r -> "- " + (r.getFileName() != null ? r.getFileName() : "文档") +
                              ": " + r.getTextContent().substring(0, Math.min(100, r.getTextContent().length())))
                    .collect(Collectors.joining("\n"));

            String prompt = REFLECTION_PROMPT
                    .replace("{question}", question)
                    .replace("{count}", String.valueOf(results.size()))
                    .replace("{summary}", summary);

            String response = chatService.chat(prompt);
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                com.fasterxml.jackson.databind.JsonNode node =
                        objectMapper.readTree(response.substring(start, end + 1));
                return node.path("score").asInt(60);
            }
        } catch (Exception e) {
            log.warn("[ReflectionAgent] 评估LLM调用失败，使用默认分数: {}", e.getMessage());
        }
        return results.size() >= 2 ? 70 : 40;
    }

    private void triggerSummary(AgentTask originalTask, List<SearchResult> results, int reflectionScore) {
        AgentTask summaryTask = AgentTask.builder()
                .taskId(UUID.randomUUID().toString())
                .correlationId(originalTask.getCorrelationId())
                .type(AgentTask.TaskType.SUMMARY)
                .question(originalTask.getQuestion())
                .userId(originalTask.getUserId())
                .sessionId(originalTask.getSessionId())
                .params(java.util.Map.of("reflectionScore", reflectionScore))
                .createdAt(LocalDateTime.now())
                .build();

        try {
            kafkaTemplate.send(MultiAgentKafkaOrchestrator.TOPIC_SUMMARY_TASKS,
                    originalTask.getCorrelationId(), objectMapper.writeValueAsString(summaryTask));
            log.info("[ReflectionAgent] 触发汇总生成: correlationId={}, score={}",
                    originalTask.getCorrelationId(), reflectionScore);
        } catch (Exception e) {
            log.error("[ReflectionAgent] 触发汇总失败: {}", e.getMessage(), e);
        }
    }
}
