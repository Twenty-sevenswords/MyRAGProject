package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IntentAgent {

    private static final Logger logger = LoggerFactory.getLogger(IntentAgent.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiProperties aiProperties;

    public AgentIntent analyze(String message, String sessionId) {
        logger.info("[IntentAgent] ========== 开始分析意图 ==========");
        logger.info("[IntentAgent] 会话ID: {}, 用户消息: {}", sessionId, message);

        try {
            String prompt = buildPrompt(message);
            logger.debug("[IntentAgent] 构建的Prompt:\n{}", prompt);

            logger.info("[IntentAgent] 正在调用 LLM 分析意图...");
            String response = deepSeekClient.chat(prompt);
            logger.info("[IntentAgent] LLM 原始响应: {}", response);

            // 解析JSON响应
            AgentIntent intent = parseResponse(response);
            intent.setSessionId(sessionId);
            intent.setRawMessage(message);

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

    private String buildPrompt(String message) {
        String template = aiProperties.getAgent().getIntent().getTemplate();
        return template.replace("{message}", message);
    }

    private AgentIntent parseResponse(String response) {
        try {
            // 提取JSON部分
            String json = extractJson(response);
            logger.debug("[IntentAgent] 提取的JSON: {}", json);
            AgentIntent intent = objectMapper.readValue(json, AgentIntent.class);
            logger.info("[IntentAgent] JSON解析成功");
            return intent;
        } catch (Exception e) {
            logger.error("[IntentAgent] 解析意图失败，响应内容: {}", response, e);
            throw new RuntimeException("解析意图失败: " + response);
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}