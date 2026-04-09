package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查 Agent
 * 使用 LangChain4j 进行质量检查
 */
@Component
public class CheckAgent {

    private static final Logger logger = LoggerFactory.getLogger(CheckAgent.class);

    private final LangChain4jChatService chatService;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    public CheckAgent(LangChain4jChatService chatService,
                      ObjectMapper objectMapper,
                      AiProperties aiProperties) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
    }

    // 快速规则检查（零成本）
    public boolean quickCheck(AgentResult result, AgentIntent intent) {
        logger.info("[CheckAgent] ========== 开始快速检查 ==========");
        logger.info("[CheckAgent] 回复长度: {}, 使用LLM: {}",
                result.getReply() != null ? result.getReply().length() : 0,
                result.isUsedLLM());

        // 1. 空检查
        int minReplyLength = aiProperties.getAgent().getCheck().getMinReplyLength();
        if (result.getReply() == null || result.getReply().length() < minReplyLength) {
            logger.warn("[CheckAgent] >>> 快速检查不通过: 回复为空或过短 (长度 < {})", minReplyLength);
            logger.info("[CheckAgent] ========== 快速检查完成: FAILED ==========");
            return false;
        }
        logger.debug("[CheckAgent] ✓ 空检查通过");

        // 2. LLM回复必须有来源标注
        String sourceMarker = aiProperties.getAgent().getCheck().getSourceMarker();
        if (result.isUsedLLM() && !result.getReply().contains(sourceMarker)) {
            logger.warn("[CheckAgent] >>> 快速检查不通过: LLM回复缺少来源标注");
            logger.info("[CheckAgent] ========== 快速检查完成: FAILED ==========");
            return false;
        }
        if (result.isUsedLLM()) {
            logger.debug("[CheckAgent] ✓ 来源标注检查通过");
        }

        // 3. 关键词匹配检查
        List<String> keywords = intent.getKeywords();
        if (!keywords.isEmpty()) {
            long matchCount = keywords.stream()
                    .filter(kw -> result.getReply().contains(kw))
                    .count();

            logger.info("[CheckAgent] 关键词匹配检查: 关键词={}, 匹配数={}/{}",
                    keywords, matchCount, keywords.size());

            if (matchCount == 0) {
                logger.warn("[CheckAgent] >>> 快速检查不通过: 关键词完全不匹配，疑似幻觉");
                logger.info("[CheckAgent] ========== 快速检查完成: FAILED ==========");
                return false; // 完全不匹配，疑似幻觉
            }
            logger.debug("[CheckAgent] ✓ 关键词匹配检查通过");
        }

        logger.info("[CheckAgent] ========== 快速检查完成: PASSED ==========");
        return true;
    }

    // 深度检查（LLM复核，仅规则失败时调用）
    public boolean deepCheck(AgentResult result, String originalMessage) {
        logger.info("[CheckAgent] ========== 开始深度检查 ==========");
        logger.info("[CheckAgent] 原始消息: {}", originalMessage);
        logger.info("[CheckAgent] 回复长度: {}, 来源数量: {}",
                result.getReply().length(),
                result.getSources() != null ? result.getSources().size() : 0);

        try {
            // 构建检查 prompt
            String checkPrompt = buildCheckPrompt(result, originalMessage);
            logger.debug("[CheckAgent] LLM检查Prompt: {}", checkPrompt);

            // 调用 LangChain4j 进行检查
            String llmResponse = chatService.chat(checkPrompt);
            logger.info("[CheckAgent] LLM检查响应: {}", llmResponse);

            // 解析 LLM 响应
            boolean passed = parseCheckResult(llmResponse);

            if (passed) {
                logger.info("[CheckAgent] ========== 深度检查完成: PASSED (LLM判定) ==========");
            } else {
                logger.warn("[CheckAgent] ========== 深度检查完成: FAILED (LLM判定) ==========");
            }
            return passed;

        } catch (Exception e) {
            logger.error("[CheckAgent] LLM深度检查异常: {}", e.getMessage(), e);
            // 降级：使用启发式规则
            return fallbackDeepCheck(result);
        }
    }

    /**
     * 构建检查 prompt
     */
    private String buildCheckPrompt(AgentResult result, String originalMessage) {
        String template = aiProperties.getAgent().getCheck().getTemplate();
        return template
                .replace("{question}", originalMessage)
                .replace("{answer}", result.getReply());
    }

    /**
     * 解析 LLM 检查结果
     */
    private boolean parseCheckResult(String llmResponse) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            logger.warn("[CheckAgent] LLM响应为空，默认通过");
            return true;
        }

        try {
            // 提取 JSON 部分
            String json = extractJson(llmResponse);
            logger.debug("[CheckAgent] 提取的JSON: {}", json);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);

            Boolean passed = (Boolean) result.get("passed");
            Object scoreObj = result.get("score");
            String reason = (String) result.get("reason");

            int score = 0;
            if (scoreObj instanceof Number) {
                score = ((Number) scoreObj).intValue();
            }

            logger.info("[CheckAgent] LLM判定结果: passed={}, score={}, reason={}",
                    passed, score, reason);

            // 如果 passed 为 null，根据 score 判断
            if (passed == null) {
                return score >= 6;
            }
            return passed;

        } catch (Exception e) {
            logger.error("[CheckAgent] 解析LLM响应失败: {}", e.getMessage());
            // 解析失败时，检查响应中是否包含 "passed": true
            return llmResponse.contains("\"passed\": true") ||
                   llmResponse.contains("\"passed\":true");
        }
    }

    /**
     * 从文本中提取 JSON
     */
    private String extractJson(String text) {
        // 尝试匹配 JSON 对象
        Pattern pattern = Pattern.compile("\\{[^{}]*\"passed\"[^{}]*\\}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }

        // 降级：提取第一个完整的 JSON 对象
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 降级检查（启发式规则）
     */
    private boolean fallbackDeepCheck(AgentResult result) {
        logger.info("[CheckAgent] 使用降级规则检查");
        int replyLength = result.getReply().length();
        int sourceCount = result.getSources() != null ? result.getSources().size() : 0;
        int maxReplyLength = aiProperties.getAgent().getCheck().getMaxReplyLength();

        if (replyLength > maxReplyLength && sourceCount <= 2) {
            logger.warn("[CheckAgent] 降级检查不通过: 来源少({})但回复长({})", sourceCount, replyLength);
            return false;
        }
        return true;
    }
}
