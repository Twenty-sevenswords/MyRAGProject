package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import com.yizhaoqi.smartpai.util.JsonExtractor;
import com.yizhaoqi.smartpai.util.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document grading node for relevance filtering.
 */
@Component
public class GradingNode implements StreamingNodeAction {

    private static final Logger log = LoggerFactory.getLogger(GradingNode.class);

    private static final String FALLBACK_GRADE_PROMPT = """
            请判断以下文档片段与用户问题的相关性。
            用户问题：{question}

            文档片段：{document}

            评分标准：
            - 10分：文档直接回答了问题
            - 7-9分：文档包含高度相关信息
            - 4-6分：文档部分相关
            - 0-3分：文档无关
            请仅返回 JSON：{"score": 0-10}
            """;

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d{1,2})");
    private static final int RELEVANCE_THRESHOLD = 6;
    private static final int MIN_SUFFICIENT_DOCS = 2;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private PromptLoader promptLoader;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "grading";
    }

    @Override
    public AIState apply(AIState state) {
        List<SearchResult> rawResults = state.getSearchResults();
        String question = state.getRewrittenQuery() != null
                ? state.getRewrittenQuery() : state.getCurrentMessage();

        log.info("[GradingNode] Start grading, docs={}, question={}", rawResults.size(), question);

        if (rawResults.isEmpty()) {
            state.setGradedResults(new ArrayList<>());
            state.setSufficientContext(false);
            log.warn("[GradingNode] No retrieval results, sufficientContext=false");
            return state;
        }

        List<SearchResult> passed = new ArrayList<>();
        for (SearchResult doc : rawResults) {
            try {
                int score = gradeDocument(question, doc.getTextContent());
                log.debug("[GradingNode] score={} text={}", score, truncate(doc.getTextContent(), 40));
                if (score >= RELEVANCE_THRESHOLD) {
                    passed.add(doc);
                }
            } catch (Exception e) {
                log.warn("[GradingNode] grading failed, keep document by fallback: {}", e.getMessage());
                passed.add(doc);
            }
        }

        state.setGradedResults(passed);
        state.setSufficientContext(passed.size() >= MIN_SUFFICIENT_DOCS);

        log.info("[GradingNode] Finished grading: {}/{} passed, sufficientContext={}",
                passed.size(), rawResults.size(), state.isSufficientContext());
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    private int gradeDocument(String question, String docContent) {
        String template = promptLoader.loadWithFallback("prompts/document-grading.txt", FALLBACK_GRADE_PROMPT);
        String prompt = promptLoader.render(template, Map.of(
                "question", question == null ? "" : question,
                "document", truncate(docContent, 500)
        ));

        String response = chatService.chat(prompt).trim();

        // Preferred path: parse JSON score.
        try {
            Map<String, Object> result = JsonExtractor.parse(response,
                    new TypeReference<Map<String, Object>>() {
                    },
                    objectMapper);
            return clampScore(result.get("score"));
        } catch (Exception ignored) {
            // Fallback path: regex extraction keeps compatibility with imperfect LLM outputs.
            return fallbackExtractScore(response);
        }
    }

    private int clampScore(Object scoreValue) {
        int score;
        if (scoreValue instanceof Number number) {
            score = number.intValue();
        } else {
            score = Integer.parseInt(String.valueOf(scoreValue));
        }
        return Math.max(0, Math.min(10, score));
    }

    private int fallbackExtractScore(String response) {
        Matcher matcher = SCORE_PATTERN.matcher(response == null ? "" : response);
        if (matcher.find()) {
            int score = Integer.parseInt(matcher.group(1));
            return Math.max(0, Math.min(10, score));
        }
        return 5;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
