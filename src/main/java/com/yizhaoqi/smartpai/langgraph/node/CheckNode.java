package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查节点 - 复检Agent
 * 检查答案是否hallucination、是否完整、是否合规
 */
@Component
public class CheckNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(CheckNode.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiProperties aiProperties;

    @Override
    public String getName() {
        return "check";
    }

    @Override
    public AIState apply(AIState state) {
        logger.info("[CheckNode] ========== 开始质量检查 ==========");
        logger.info("[CheckNode] 回复长度: {}, 使用LLM: {}",
                state.getGeneratedReply() != null ? state.getGeneratedReply().length() : 0,
                state.isUsedLLM());

        String reply = state.getGeneratedReply();
        AgentIntent intent = state.getIntent();
        String originalMessage = state.getCurrentMessage();

        // 快速检查
        boolean passed = quickCheck(reply, intent, state);
        logger.info("[CheckNode] 快速检查结果: {}", passed ? "通过" : "不通过");

        if (!passed) {
            // 深度检查
            logger.info("[CheckNode] 快速检查未通过，执行深度检查...");
            passed = deepCheck(reply, originalMessage, state);
            logger.info("[CheckNode] 深度检查结果: {}", passed ? "通过" : "不通过");
        }

        // 更新状态
        state.setCheckPassed(passed);

        // 根据检查结果决定最终输出
        if (passed) {
            logger.info("[CheckNode] >>> 质检通过分支: 输出正常回复");
            state.setFinalReply(state.getGeneratedReply());
        } else {
            logger.warn("[CheckNode] >>> 质检未通过分支: 触发降级策略");
            state.setFinalReply(generateFallbackReply(state));
        }

        logger.info("[CheckNode] ========== 质量检查完成 ==========");
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    /**
     * 快速检查（零成本）
     */
    private boolean quickCheck(String reply, AgentIntent intent, AIState state) {
        logger.info("[CheckNode] ========== 开始快速检查 ==========");

        // 1. 空检查
        int minReplyLength = aiProperties.getAgent().getCheck().getMinReplyLength();
        if (reply == null || reply.length() < minReplyLength) {
            logger.warn("[CheckNode] >>> 快速检查不通过: 回复为空或过短 (长度 < {})", minReplyLength);
            state.setCheckReason("回复为空或过短");
            return false;
        }
        logger.debug("[CheckNode] ✓ 空检查通过");

        // 2. LLM回复必须有来源标注
        String sourceMarker = aiProperties.getAgent().getCheck().getSourceMarker();
        if (state.isUsedLLM() && !reply.contains(sourceMarker)) {
            logger.warn("[CheckNode] >>> 快速检查不通过: LLM回复缺少来源标注");
            state.setCheckReason("LLM回复缺少来源标注");
            return false;
        }
        if (state.isUsedLLM()) {
            logger.debug("[CheckNode] ✓ 来源标注检查通过");
        }

        // 3. 关键词匹配检查
        if (intent != null && intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            List<String> keywords = intent.getKeywords();
            long matchCount = keywords.stream()
                    .filter(kw -> reply.contains(kw))
                    .count();

            logger.info("[CheckNode] 关键词匹配检查: 关键词={}, 匹配数={}/{}",
                    keywords, matchCount, keywords.size());

            if (matchCount == 0) {
                logger.warn("[CheckNode] >>> 快速检查不通过: 关键词完全不匹配，疑似幻觉");
                state.setCheckReason("关键词完全不匹配，疑似幻觉");
                return false;
            }
            logger.debug("[CheckNode] ✓ 关键词匹配检查通过");
        }

        logger.info("[CheckNode] ========== 快速检查完成: PASSED ==========");
        state.setCheckScore(8);
        return true;
    }

    /**
     * 深度检查（LLM复核）
     */
    private boolean deepCheck(String reply, String originalMessage, AIState state) {
        logger.info("[CheckNode] ========== 开始深度检查 ==========");
        logger.info("[CheckNode] 原始消息: {}", originalMessage);
        logger.info("[CheckNode] 回复长度: {}, 来源数量: {}",
                reply.length(),
                state.getSources() != null ? state.getSources().size() : 0);

        try {
            // 构建检查 prompt
            String checkPrompt = buildCheckPrompt(reply, originalMessage);
            logger.debug("[CheckNode] LLM检查Prompt: {}", checkPrompt);

            // 调用 LLM 进行检查
            String llmResponse = deepSeekClient.chat(checkPrompt);
            logger.info("[CheckNode] LLM检查响应: {}", llmResponse);

            // 解析 LLM 响应
            boolean passed = parseCheckResult(llmResponse, state);

            if (passed) {
                logger.info("[CheckNode] ========== 深度检查完成: PASSED (LLM判定) ==========");
            } else {
                logger.warn("[CheckNode] ========== 深度检查完成: FAILED (LLM判定) ==========");
            }
            return passed;

        } catch (Exception e) {
            logger.error("[CheckNode] LLM深度检查异常: {}", e.getMessage(), e);
            // 降级：使用启发式规则
            return fallbackDeepCheck(state);
        }
    }

    /**
     * 构建检查 prompt
     */
    private String buildCheckPrompt(String reply, String originalMessage) {
        String template = aiProperties.getAgent().getCheck().getTemplate();
        return template
                .replace("{question}", originalMessage)
                .replace("{answer}", reply);
    }

    /**
     * 解析 LLM 检查结果
     */
    private boolean parseCheckResult(String llmResponse, AIState state) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            logger.warn("[CheckNode] LLM响应为空，默认通过");
            return true;
        }

        try {
            // 提取 JSON 部分
            String json = extractJson(llmResponse);
            logger.debug("[CheckNode] 提取的JSON: {}", json);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);

            Boolean passed = (Boolean) result.get("passed");
            Object scoreObj = result.get("score");
            String reason = (String) result.get("reason");

            int score = 0;
            if (scoreObj instanceof Number) {
                score = ((Number) scoreObj).intValue();
            }

            logger.info("[CheckNode] LLM判定结果: passed={}, score={}, reason={}",
                    passed, score, reason);

            // 更新状态
            state.setCheckScore(score);
            state.setCheckReason(reason);

            // 如果 passed 为 null，根据 score 判断
            if (passed == null) {
                return score >= 6;
            }
            return passed;

        } catch (Exception e) {
            logger.error("[CheckNode] 解析LLM响应失败: {}", e.getMessage());
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
    private boolean fallbackDeepCheck(AIState state) {
        logger.info("[CheckNode] 使用降级规则检查");
        int replyLength = state.getGeneratedReply() != null ? state.getGeneratedReply().length() : 0;
        int sourceCount = state.getSources() != null ? state.getSources().size() : 0;
        int maxReplyLength = aiProperties.getAgent().getCheck().getMaxReplyLength();

        if (replyLength > maxReplyLength && sourceCount <= 2) {
            logger.warn("[CheckNode] 降级检查不通过: 来源少({})但回复长({})", sourceCount, replyLength);
            state.setCheckReason("来源少但回复长，疑似幻觉");
            return false;
        }
        state.setCheckReason("降级检查通过");
        return true;
    }

    /**
     * 生成降级回复
     */
    private String generateFallbackReply(AIState state) {
        List<?> sources = state.getSources();
        if (sources != null && !sources.isEmpty()) {
            // 有检索结果，返回来源列表
            StringBuilder sb = new StringBuilder("抱歉，系统无法生成准确回复。以下是与您问题相关的文档片段：\n\n");
            for (int i = 0; i < Math.min(3, sources.size()); i++) {
                Object obj = sources.get(i);
                if (obj instanceof SearchResult) {
                    SearchResult r = (SearchResult) obj;
                    String fileName = r.getFileName() != null ? r.getFileName() : r.getFileMd5().substring(0, 8);
                    sb.append(String.format("• %s (片段#%d)\n", fileName, r.getChunkId()));
                }
            }
            sb.append("\n请尝试更具体的问题描述。");
            return sb.toString();
        }
        return "系统暂时无法生成准确回复，请尝试更具体的问题描述。";
    }
}
