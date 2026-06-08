package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 汇总生成 Agent 消费者
 *
 * 职责：
 * 接收反思评估后的高质量文档，调用 LLM 生成最终回复，
 * 并通过 MultiAgentKafkaOrchestrator 回调推送给等待的 WebSocket 连接。
 */
@Component
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class SummaryAgentConsumer {

    private static final Logger log = LoggerFactory.getLogger(SummaryAgentConsumer.class);

    private static final String SUMMARY_PROMPT = """
            你是派聪明知识助手。请基于以下检索文档回答用户问题。

            参考文档：
            {context}

            用户问题：{question}

            要求：
            1. 直接回答问题，简明扼要
            2. 引用文档内容时标注来源：【来源#编号: 文件名】
            3. 若文档不足以回答，明确说明"暂无相关信息"
            """;

    @Autowired
    private AgentStateManager stateManager;

    @Autowired
    private MultiAgentKafkaOrchestrator orchestrator;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(
            topics = MultiAgentKafkaOrchestrator.TOPIC_SUMMARY_TASKS,
            groupId = "agent-summary-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSummaryTask(String message) {
        try {
            AgentTask task = objectMapper.readValue(message, AgentTask.class);
            log.info("[SummaryAgent] 开始汇总生成: correlationId={}", task.getCorrelationId());

            // 获取合并后的检索结果
            List<SearchResult> results = stateManager.getMergedSearchResults(task.getCorrelationId());

            // 构建上下文
            String context = buildContext(results);

            // 生成回复
            String prompt = SUMMARY_PROMPT
                    .replace("{context}", context)
                    .replace("{question}", task.getQuestion());

            String reply = chatService.chat(prompt);
            log.info("[SummaryAgent] 回复生成完成，长度: {}", reply != null ? reply.length() : 0);

            // 标记任务完成
            stateManager.markDone(task.getCorrelationId());

            // 回调编排器，推送给等待的 WebSocket
            orchestrator.onSummaryComplete(task.getCorrelationId(), reply);

        } catch (Exception e) {
            log.error("[SummaryAgent] 汇总生成失败: {}", e.getMessage(), e);
            try {
                AgentTask task = objectMapper.readValue(message, AgentTask.class);
                orchestrator.onSummaryComplete(task.getCorrelationId(), "系统生成回复时出现错误，请稍后重试。");
            } catch (Exception ex) {
                log.error("[SummaryAgent] 错误回调失败: {}", ex.getMessage());
            }
        }
    }

    private String buildContext(List<SearchResult> results) {
        if (results.isEmpty()) return "（未检索到相关文档）";
        return results.stream()
                .limit(6)
                .map(r -> "【来源#" + (results.indexOf(r) + 1) + ": " +
                          (r.getFileName() != null ? r.getFileName() : "文档") + "】\n" +
                          r.getTextContent())
                .collect(Collectors.joining("\n\n"));
    }
}
