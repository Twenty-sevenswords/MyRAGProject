package com.yizhaoqi.smartpai.service.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.util.JsonExtractor;
import com.yizhaoqi.smartpai.util.PromptLoader;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Intent recognition and parsing service.
 */
@Service
public class IntentRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionService.class);

    private static final String DEFAULT_INTENT_PROMPT = """
            You are an intent classifier.
            Classify the user message intent into SEARCH / QA / COMPARE / SUMMARIZE / CHAT.
            Return JSON only:
            {"intent":"...", "confidence":0.0, "keywords":["..."], "complexity":1, "needsHistory":false}
            message: {message}
            """;

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;

    public IntentRecognitionService(ChatLanguageModel chatModel, ObjectMapper objectMapper, PromptLoader promptLoader) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    public IntentRecognitionResult recognizeIntent(String userMessage) {
        String template = promptLoader.loadWithFallback("prompts/intent-recognition.txt", DEFAULT_INTENT_PROMPT);
        return recognizeIntentWithPrompt(template, userMessage);
    }

    public IntentRecognitionResult recognizeIntentWithPrompt(String promptTemplate, String userMessage) {
        try {
            String prompt = promptTemplate.replace("{message}", userMessage);
            String response = chatModel.chat(prompt);
            return parseIntentResult(response, userMessage);
        } catch (Exception e) {
            logger.error("[IntentRecognitionService] intent recognition failed", e);
            IntentRecognitionResult fallback = new IntentRecognitionResult();
            fallback.setIntent("UNKNOWN");
            fallback.setConfidence(0.3);
            fallback.setKeywords(List.of(userMessage.split("\\s+")));
            fallback.setComplexity(2);
            fallback.setNeedsHistory(false);
            return fallback;
        }
    }

    private IntentRecognitionResult parseIntentResult(String response, String userMessage) {
        IntentRecognitionResult result = new IntentRecognitionResult();
        try {
            String json = JsonExtractor.extract(response);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            result.setIntent(String.valueOf(map.getOrDefault("intent", "CHAT")));
            result.setConfidence(asDouble(map.get("confidence"), 0.85));
            result.setComplexity(asInt(map.get("complexity"), 2));
            result.setNeedsHistory(asBoolean(map.get("needsHistory"), false));
            result.setKeywords(asKeywords(map.get("keywords"), userMessage));
            return result;
        } catch (Exception e) {
            logger.warn("[IntentRecognitionService] JSON parse failed, fallback semantic parse: {}", e.getMessage());
            result.setIntent(fallbackIntent(response));
            result.setConfidence(0.85);
            result.setComplexity(2);
            result.setNeedsHistory(false);
            result.setKeywords(List.of(userMessage.split("\\s+")));
            return result;
        }
    }

    private String fallbackIntent(String response) {
        if (response == null) {
            return "CHAT";
        }
        if (response.contains("SEARCH")) {
            return "SEARCH";
        }
        if (response.contains("COMPARE")) {
            return "COMPARE";
        }
        if (response.contains("SUMMARIZE")) {
            return "SUMMARIZE";
        }
        if (response.contains("QA")) {
            return "QA";
        }
        return "CHAT";
    }

    private double asDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> asKeywords(Object value, String userMessage) {
        if (value instanceof List<?>) {
            List<String> keywords = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    keywords.add(String.valueOf(item));
                }
            }
            if (!keywords.isEmpty()) {
                return keywords;
            }
        }
        return List.of(userMessage.split("\\s+"));
    }

    public static class IntentRecognitionResult {
        private String intent;
        private double confidence;
        private List<String> keywords = new ArrayList<>();
        private int complexity;
        private boolean needsHistory;

        public String getIntent() {
            return intent;
        }

        public void setIntent(String intent) {
            this.intent = intent;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public int getComplexity() {
            return complexity;
        }

        public void setComplexity(int complexity) {
            this.complexity = complexity;
        }

        public boolean isNeedsHistory() {
            return needsHistory;
        }

        public void setNeedsHistory(boolean needsHistory) {
            this.needsHistory = needsHistory;
        }
    }
}
