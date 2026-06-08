package com.yizhaoqi.smartpai.service.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.util.JsonExtractor;
import com.yizhaoqi.smartpai.util.PromptLoader;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based answer quality checking service.
 */
@Service
public class QualityCheckService {

    private static final String QUALITY_PROMPT = """
            You are a quality evaluator.
            Evaluate whether the answer addresses the question.
            Return JSON only:
            {"passed": true/false, "score": 0-100, "reason":"..."}
            """;

    private final ChatLanguageModel chatModel;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public QualityCheckService(ChatLanguageModel chatModel,
                               PromptLoader promptLoader,
                               ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    public QualityCheckResult checkQuality(String question, String answer) {
        String qualityPrompt = promptLoader.loadWithFallback("prompts/quality-check.txt", QUALITY_PROMPT);
        String userPrompt = String.format("question: %s%nanswer: %s", question, answer);

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(qualityPrompt));
        messages.add(UserMessage.from(userPrompt));

        ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
        String text = response.aiMessage().text();
        return parseQualityResult(text);
    }

    private QualityCheckResult parseQualityResult(String rawResponse) {
        QualityCheckResult result = new QualityCheckResult();
        try {
            Map<String, Object> json = JsonExtractor.parse(rawResponse,
                    new TypeReference<Map<String, Object>>() {
                    },
                    objectMapper);

            result.setPassed(asBoolean(json.get("passed"), rawResponse));
            result.setScore(asInt(json.get("score"), extractScore(rawResponse)));
            result.setReason(String.valueOf(json.getOrDefault("reason", rawResponse)));
            return result;
        } catch (Exception ignored) {
            result.setPassed(rawResponse != null && (rawResponse.contains("true") || rawResponse.contains("passed")));
            result.setScore(extractScore(rawResponse));
            result.setReason(rawResponse);
            return result;
        }
    }

    private boolean asBoolean(Object value, String fallbackText) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallbackText != null && fallbackText.contains("true");
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int extractScore(String response) {
        if (response == null) {
            return 70;
        }
        try {
            Pattern primary = Pattern.compile("score\\s*[:=]\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
            Matcher primaryMatcher = primary.matcher(response);
            if (primaryMatcher.find()) {
                return Integer.parseInt(primaryMatcher.group(1));
            }

            // Fallback: capture first standalone integer in the response text.
            Pattern fallback = Pattern.compile("\\b(\\d{1,3})\\b");
            Matcher fallbackMatcher = fallback.matcher(response);
            if (fallbackMatcher.find()) {
                return Integer.parseInt(fallbackMatcher.group(1));
            }
        } catch (Exception ignored) {
            // Ignore and use fallback score.
        }
        return 70;
    }

    public static class QualityCheckResult {
        private boolean passed;
        private int score;
        private String reason;

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
