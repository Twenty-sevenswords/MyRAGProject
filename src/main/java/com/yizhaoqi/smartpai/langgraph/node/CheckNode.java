package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.properties.AiProperties;
import com.yizhaoqi.smartpai.dto.AgentIntent;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import com.yizhaoqi.smartpai.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Final quality gate node.
 */
@Component
public class CheckNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(CheckNode.class);

    @Autowired
    private LangChain4jChatService chatService;

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
        String reply = state.getGeneratedReply();
        AgentIntent intent = state.getIntent();

        boolean passed = quickCheck(reply, intent, state);
        if (!passed) {
            passed = deepCheck(reply, state.getCurrentMessage(), state);
        }

        state.setCheckPassed(passed);
        if (passed) {
            state.setFinalReply(state.getGeneratedReply());
        } else {
            state.setFinalReply(generateFallbackReply(state));
        }
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    private boolean quickCheck(String reply, AgentIntent intent, AIState state) {
        int minReplyLength = aiProperties.getAgent().getCheck().getMinReplyLength();
        if (reply == null || reply.length() < minReplyLength) {
            state.setCheckReason("Reply is empty or too short");
            return false;
        }

        String sourceMarker = aiProperties.getAgent().getCheck().getSourceMarker();
        if (state.isUsedLLM() && !reply.contains(sourceMarker)) {
            state.setCheckReason("Missing source marker");
            return false;
        }

        if (intent != null && intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            long matchCount = intent.getKeywords().stream().filter(reply::contains).count();
            if (matchCount == 0) {
                state.setCheckReason("No keyword matched");
                return false;
            }
        }

        state.setCheckScore(8);
        state.setCheckReason("Quick check passed");
        return true;
    }

    private boolean deepCheck(String reply, String originalMessage, AIState state) {
        try {
            String checkPrompt = buildCheckPrompt(reply, originalMessage);
            String llmResponse = chatService.chat(checkPrompt);
            return parseCheckResult(llmResponse, state);
        } catch (Exception e) {
            logger.warn("[CheckNode] Deep check failed, fallback heuristic enabled: {}", e.getMessage());
            return fallbackDeepCheck(state);
        }
    }

    private String buildCheckPrompt(String reply, String originalMessage) {
        String template = aiProperties.getAgent().getCheck().getTemplate();
        return template
                .replace("{question}", originalMessage)
                .replace("{answer}", reply);
    }

    private boolean parseCheckResult(String llmResponse, AIState state) {
        if (llmResponse == null || llmResponse.isEmpty()) {
            return true;
        }

        try {
            Map<String, Object> result = JsonExtractor.parse(
                    llmResponse,
                    new TypeReference<Map<String, Object>>() {
                    },
                    objectMapper
            );

            Boolean passed = asBoolean(result.get("passed"));
            int score = asInt(result.get("score"), 0);
            String reason = result.get("reason") == null ? "" : result.get("reason").toString();

            state.setCheckScore(score);
            state.setCheckReason(reason);

            return passed != null ? passed : score >= 6;
        } catch (Exception e) {
            logger.error("[CheckNode] Failed to parse deep-check response", e);
            return llmResponse.contains("\"passed\": true") || llmResponse.contains("\"passed\":true");
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean fallbackDeepCheck(AIState state) {
        int replyLength = state.getGeneratedReply() != null ? state.getGeneratedReply().length() : 0;
        int sourceCount = state.getSources() != null ? state.getSources().size() : 0;
        int maxReplyLength = aiProperties.getAgent().getCheck().getMaxReplyLength();

        if (replyLength > maxReplyLength && sourceCount <= 2) {
            state.setCheckReason("Too long with too few sources");
            return false;
        }
        state.setCheckReason("Fallback check passed");
        return true;
    }

    private String generateFallbackReply(AIState state) {
        List<?> sources = state.getSources();
        if (sources != null && !sources.isEmpty()) {
            StringBuilder sb = new StringBuilder("Unable to produce a reliable answer. Relevant snippets:\n\n");
            for (int i = 0; i < Math.min(3, sources.size()); i++) {
                Object source = sources.get(i);
                if (source instanceof SearchResult) {
                    SearchResult r = (SearchResult) source;
                    String fileName = r.getFileName() != null ? r.getFileName() : r.getFileMd5().substring(0, 8);
                    sb.append("- ").append(fileName).append(" (chunk#").append(r.getChunkId()).append(")\n");
                }
            }
            sb.append("\nPlease try a more specific question.");
            return sb.toString();
        }
        return "Unable to produce a reliable answer right now. Please try a more specific question.";
    }
}
