package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.service.LangChain4jAgentService;
import com.yizhaoqi.smartpai.service.LangChain4jAgentService.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 意图识别 Agent
 * 使用 LangChain4j 进行意图识别
 */
@Component
public class IntentAgent {

    private static final Logger logger = LoggerFactory.getLogger(IntentAgent.class);

    private final LangChain4jAgentService langChain4jAgentService;
    private final AiProperties aiProperties;

    public IntentAgent(LangChain4jAgentService langChain4jAgentService,
                       AiProperties aiProperties) {
        this.langChain4jAgentService = langChain4jAgentService;
        this.aiProperties = aiProperties;
    }

    /**
     * 分析用户消息的意图
     * @param message 用户消息
     * @param sessionId 会话ID
     * @return 识别出的意图
     */
    public AgentIntent analyze(String message, String sessionId) {
        logger.info("[IntentAgent] ========== 开始分析意图 ==========");
        logger.info("[IntentAgent] 会话ID: {}, 用户消息: {}", sessionId, message);

        try {
            // 获取 prompt 模板
            String promptTemplate = buildPromptTemplate();
            logger.debug("[IntentAgent] 使用的Prompt模板:\n{}", promptTemplate);

            // 使用 LangChain4j 进行意图识别
            IntentResult result = langChain4jAgentService.recognizeIntentWithPrompt(promptTemplate, message);

            // 转换为 AgentIntent
            AgentIntent intent = convertToAgentIntent(result, message, sessionId);

            logger.info("[IntentAgent] 解析结果: intent={}, confidence={}, complexity={}, needsHistory={}, keywords={}",
                    intent.getIntent(), intent.getConfidence(), intent.getComplexity(),
                    intent.isNeedsHistory(), intent.getKeywords());
            logger.info("[IntentAgent] ========== 意图分析完成 ==========");

            return intent;

        } catch (Exception e) {
            logger.error("[IntentAgent] 意图分析异常: {}", e.getMessage(), e);
            // 降级：返回UNKNOWN意图
            AgentIntent fallback = AgentIntent.unknown(message, sessionId, e.getMessage());
            logger.warn("[IntentAgent] 降级返回 UNKNOWN 意图");
            return fallback;
        }
    }

    /**
     * 构建 prompt 模板
     */
    private String buildPromptTemplate() {
        String template = aiProperties.getAgent().getIntent().getTemplate();
        if (template == null || template.isEmpty()) {
            // 默认模板
            return """
                    你是一个意图识别专家。请分析用户消息，识别其意图。
                    
                    可能的意图类型：
                    - SEARCH: 搜索/查找信息
                    - QA: 问答
                    - COMPARE: 比较/对比
                    - SUMMARIZE: 总结/摘要
                    - CHAT: 普通聊天
                    
                    请以 JSON 格式返回结果：
                    {"intent": "意图类型", "confidence": 0.0-1.0, "complexity": 1-5, "needsHistory": true/false, "keywords": ["关键词1", "关键词2"]}
                    
                    用户消息：{message}
                    """;
        }
        return template;
    }

    /**
     * 将 LangChain4j 的 IntentResult 转换为 AgentIntent
     */
    private AgentIntent convertToAgentIntent(IntentResult result, String message, String sessionId) {
        AgentIntent intent = new AgentIntent();
        intent.setIntent(result.getIntent());
        intent.setConfidence(result.getConfidence());
        intent.setKeywords(result.getKeywords());
        intent.setComplexity(result.getComplexity());
        intent.setNeedsHistory(result.isNeedsHistory());
        intent.setSessionId(sessionId);
        intent.setRawMessage(message);
        return intent;
    }
}
