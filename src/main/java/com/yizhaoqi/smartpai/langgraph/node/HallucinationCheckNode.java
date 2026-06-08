package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Self-RAG style hallucination check node.
 */
@Component
public class HallucinationCheckNode implements StreamingNodeAction {

    private static final Logger log = LoggerFactory.getLogger(HallucinationCheckNode.class);
    private static final int PASS_THRESHOLD = 70;

    private static final String CHECK_PROMPT = """
            Evaluate whether the answer is grounded in the provided documents.
            Return JSON only:
            {"score": 0-100, "passed": true/false, "reason":"..."}

            Documents:
            {documents}

            Answer:
            {reply}
            """;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "hallucinationCheck";
    }

    @Override
    public AIState apply(AIState state) {
        String reply = state.getGeneratedReply();
        List<SearchResult> docs = state.getGradedResults();

        if (reply == null || reply.isBlank()) {
            state.setHallucinationPassed(false);
            state.setHallucinationScore(0);
            return state;
        }

        if (docs == null || docs.isEmpty()) {
            boolean honestFallback = reply.contains("no relevant") || reply.contains("not found")
                    || reply.contains("无法") || reply.contains("没有");
            state.setHallucinationPassed(honestFallback);
            state.setHallucinationScore(honestFallback ? 90 : 30);
            return state;
        }

        try {
            String prompt = CHECK_PROMPT
                    .replace("{documents}", buildDocContext(docs))
                    .replace("{reply}", reply);

            String response = chatService.chat(prompt);
            parseAndApply(response, state);
        } catch (Exception e) {
            log.warn("[HallucinationCheckNode] Check failed, default pass: {}", e.getMessage());
            state.setHallucinationPassed(true);
            state.setHallucinationScore(75);
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

            int score = node.path("score").asInt(75);
            boolean passed = node.path("passed").asBoolean(score >= PASS_THRESHOLD);

            state.setHallucinationScore(score);
            state.setHallucinationPassed(passed);
        } catch (Exception e) {
            log.warn("[HallucinationCheckNode] JSON parse failed: {}", e.getMessage());
            state.setHallucinationPassed(true);
            state.setHallucinationScore(75);
        }
    }

    private String buildDocContext(List<SearchResult> docs) {
        return docs.stream()
                .limit(5)
                .map(d -> "## " + (d.getFileName() != null ? d.getFileName() : d.getFileMd5()) + "\n" + d.getTextContent())
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
