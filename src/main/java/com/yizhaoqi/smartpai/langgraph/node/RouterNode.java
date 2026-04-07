package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
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
 * 路由节点 - 意图识别Agent
 * 分析用户意图，决定后续执行路径
 * 输出路由决策：action | fallback | end
 */
@Component
public class RouterNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(RouterNode.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiProperties aiProperties;

    @Override
    public String getName() {
        return "router";
    }

    @Override
    public AIState apply(AIState state) {
        logger.info("[RouterNode] ========== 开始分析意图 ==========");
        logger.info("[RouterNode] 会话ID: {}, 用户消息: {}", state.getSessionId(), state.getCurrentMessage());

        try {
            // 构建意图分析Prompt
            String prompt = buildPrompt(state.getCurrentMessage());
            logger.debug("[RouterNode] 构建的Prompt:\n{}", prompt);

            // 调用LLM分析意图
            logger.info("[RouterNode] 正在调用 LLM 分析意图...");
            String response = deepSeekClient.chat(prompt);
            logger.info("[RouterNode] LLM 原始响应: {}", response);

            // 解析意图
            AgentIntent intent = parseResponse(response, state.getCurrentMessage(), state.getSessionId());
            
            // 更新状态
            state.setIntent(intent);

            // 路由决策
            String decision = makeRoutingDecision(intent);
            state.setRouteDecision(decision);

            logger.info("[RouterNode] 解析结果: intent={}, confidence={}, complexity={}, needsHistory={}, keywords={}",
                    intent.getIntent(), intent.getConfidence(), intent.getComplexity(),
                    intent.isNeedsHistory(), intent.getKeywords());
            logger.info("[RouterNode] 路由决策: {}", decision);
            logger.info("[RouterNode] ========== 意图分析完成 ==========");

        } catch (Exception e) {
            logger.error("[RouterNode] 意图分析异常: {}", e.getMessage(), e);
            // 降级：返回UNKNOWN意图
            AgentIntent fallback = AgentIntent.unknown(state.getCurrentMessage(), state.getSessionId(), e.getMessage());
            state.setIntent(fallback);
            state.setRouteDecision("action"); // 默认走action节点
            logger.warn("[RouterNode] 降级返回 UNKNOWN 意图");
        }

        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    /**
     * 构建意图分析Prompt
     */
    private String buildPrompt(String message) {
        String template = aiProperties.getAgent().getIntent().getTemplate();
        return template.replace("{message}", message);
    }

    /**
     * 解析LLM响应
     */
    private AgentIntent parseResponse(String response, String message, String sessionId) {
        try {
            // 提取JSON部分
            String json = extractJson(response);
            logger.debug("[RouterNode] 提取的JSON: {}", json);
            
            AgentIntent intent = objectMapper.readValue(json, AgentIntent.class);
            intent.setSessionId(sessionId);
            intent.setRawMessage(message);
            
            return intent;
        } catch (Exception e) {
            logger.error("[RouterNode] 解析意图失败，响应内容: {}", response, e);
            throw new RuntimeException("解析意图失败: " + response);
        }
    }

    /**
     * 从文本中提取JSON
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 路由决策
     */
    private String makeRoutingDecision(AgentIntent intent) {
        // 低置信度：降级处理
        if (intent.getConfidence() < 0.6) {
            logger.warn("[RouterNode] 置信度过低({}), 触发降级", intent.getConfidence());
            return "action"; // 仍然走action，但在action中会使用简单检索
        }

        // 根据意图类型路由
        switch (intent.getIntent()) {
            case "SEARCH":
            case "COMPARE":
            case "SUMMARIZE":
                return "action";
            case "CHAT":
                // 闲聊，可能不需要RAG
                return "action";
            case "UNKNOWN":
            default:
                return "action"; // 默认走action
        }
    }
}
