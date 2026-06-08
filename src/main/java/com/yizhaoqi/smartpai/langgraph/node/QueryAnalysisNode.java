package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Query analysis node for rewriting search query and deciding web-search fallback need.
 */
@Component
public class QueryAnalysisNode implements StreamingNodeAction {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisNode.class);

    private static final String REWRITE_PROMPT = """
            You are a query optimization assistant.
            Rewrite the user's question for retrieval quality while preserving intent.
            If the question needs real-time internet data, mark needsWebSearch=true.

            Recent history:
            {history}

            Original question:
            {question}

            Return JSON only:
            {"rewrittenQuery":"...", "needsWebSearch": false}
            """;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "queryAnalysis";
    }

    @Override
    public AIState apply(AIState state) {
        log.info("[QueryAnalysisNode] Start query analysis: {}", state.getCurrentMessage());

        try {
            String historyText = formatHistory(state);
            String prompt = REWRITE_PROMPT
                    .replace("{history}", historyText)
                    .replace("{question}", state.getCurrentMessage());

            String response = chatService.chat(prompt);
            parseAndApply(response, state);
        } catch (Exception e) {
            log.warn("[QueryAnalysisNode] Rewrite failed, fallback to original query: {}", e.getMessage());
            state.setRewrittenQuery(state.getCurrentMessage());
            state.setNeedsWebSearch(false);
        }

        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    private void parseAndApply(String response, AIState state) {
        try {
            String json = JsonExtractor.extract(response);
            JsonNode node = objectMapper.readTree(json);

            String rewritten = node.path("rewrittenQuery").asText(state.getCurrentMessage());
            boolean needsWeb = node.path("needsWebSearch").asBoolean(false);

            state.setRewrittenQuery(rewritten == null || rewritten.isBlank()
                    ? state.getCurrentMessage()
                    : rewritten);
            state.setNeedsWebSearch(needsWeb);
        } catch (Exception e) {
            log.warn("[QueryAnalysisNode] JSON parse failed: {}", e.getMessage());
            state.setRewrittenQuery(state.getCurrentMessage());
            state.setNeedsWebSearch(false);
        }
    }

    private String formatHistory(AIState state) {
        if (state.getChatHistory() == null || state.getChatHistory().isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        state.getRecentHistory(3).forEach(msg ->
                sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n"));
        return sb.toString();
    }
}
