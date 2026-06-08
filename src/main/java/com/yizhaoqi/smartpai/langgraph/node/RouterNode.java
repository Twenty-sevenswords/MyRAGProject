package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.properties.AiProperties;
import com.yizhaoqi.smartpai.dto.AgentIntent;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import com.yizhaoqi.smartpai.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * Router node for intent classification and route decision.
 */
@Component
public class RouterNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(RouterNode.class);

    @Autowired
    private LangChain4jChatService chatService;

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
        logger.info("[RouterNode] Start intent analysis");

        try {
            String prompt = buildPrompt(state.getCurrentMessage());
            String response = chatService.chat(prompt);

            AgentIntent intent = parseResponse(response, state.getCurrentMessage(), state.getSessionId());
            state.setIntent(intent);
            state.setRouteDecision(makeRoutingDecision(intent));

            logger.info(
                    "[RouterNode] intent={}, confidence={}, complexity={}, needsHistory={}, keywords={}",
                    intent.getIntent(),
                    intent.getConfidence(),
                    intent.getComplexity(),
                    intent.isNeedsHistory(),
                    intent.getKeywords()
            );
        } catch (Exception e) {
            logger.error("[RouterNode] Intent analysis failed", e);
            AgentIntent fallback = AgentIntent.unknown(
                    state.getCurrentMessage(),
                    state.getSessionId(),
                    e.getMessage()
            );
            state.setIntent(fallback);
            state.setRouteDecision("action");
        }

        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    private String buildPrompt(String message) {
        String template = aiProperties.getAgent().getIntent().getTemplate();
        return template.replace("{message}", message);
    }

    private AgentIntent parseResponse(String response, String message, String sessionId) {
        try {
            String json = JsonExtractor.extract(response);
            AgentIntent intent = objectMapper.readValue(json, AgentIntent.class);
            intent.setSessionId(sessionId);
            intent.setRawMessage(message);
            return intent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse intent response: " + response, e);
        }
    }

    private String makeRoutingDecision(AgentIntent intent) {
        if (intent.getConfidence() < 0.6) {
            return "action";
        }

        switch (intent.getIntent()) {
            case "SEARCH":
            case "COMPARE":
            case "SUMMARIZE":
            case "CHAT":
            case "UNKNOWN":
            default:
                return "action";
        }
    }
}
