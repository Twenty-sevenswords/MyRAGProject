package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.service.intent.IntentRecognitionService;
import com.yizhaoqi.smartpai.service.quality.QualityCheckService;
import com.yizhaoqi.smartpai.service.tool.ToolExecutionService;
import dev.langchain4j.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade service kept for backward compatibility.
 * Actual responsibilities are delegated to dedicated services:
 * - ToolExecutionService
 * - IntentRecognitionService
 * - QualityCheckService
 */
@Service
public class LangChain4jAgentService {

    private final ToolExecutionService toolExecutionService;
    private final IntentRecognitionService intentRecognitionService;
    private final QualityCheckService qualityCheckService;

    public LangChain4jAgentService(ToolExecutionService toolExecutionService,
                                   IntentRecognitionService intentRecognitionService,
                                   QualityCheckService qualityCheckService) {
        this.toolExecutionService = toolExecutionService;
        this.intentRecognitionService = intentRecognitionService;
        this.qualityCheckService = qualityCheckService;
    }

    public void registerTool(Object tool) {
        toolExecutionService.registerTool(tool);
    }

    public AgentResult execute(String userMessage) {
        return toAgentResult(toolExecutionService.execute(userMessage));
    }

    public AgentResult execute(String systemPrompt, String userMessage, ChatMemory memory) {
        return toAgentResult(toolExecutionService.execute(systemPrompt, userMessage, memory));
    }

    public IntentResult recognizeIntent(String userMessage) {
        return toIntentResult(intentRecognitionService.recognizeIntent(userMessage));
    }

    public IntentResult recognizeIntentWithPrompt(String promptTemplate, String userMessage) {
        return toIntentResult(intentRecognitionService.recognizeIntentWithPrompt(promptTemplate, userMessage));
    }

    public CheckResult checkQuality(String question, String answer) {
        return toCheckResult(qualityCheckService.checkQuality(question, answer));
    }

    private AgentResult toAgentResult(ToolExecutionService.AgentExecutionResult source) {
        if (source == null) {
            return AgentResult.failure("Unknown execution error");
        }
        if (source.isSuccess()) {
            return AgentResult.success(source.getMessage());
        }
        return AgentResult.failure(source.getError());
    }

    private IntentResult toIntentResult(IntentRecognitionService.IntentRecognitionResult source) {
        IntentResult result = new IntentResult();
        if (source == null) {
            result.setIntent("UNKNOWN");
            result.setConfidence(0.3);
            return result;
        }
        result.setIntent(source.getIntent());
        result.setConfidence(source.getConfidence());
        result.setKeywords(source.getKeywords());
        result.setComplexity(source.getComplexity());
        result.setNeedsHistory(source.isNeedsHistory());
        return result;
    }

    private CheckResult toCheckResult(QualityCheckService.QualityCheckResult source) {
        CheckResult result = new CheckResult();
        if (source == null) {
            result.setPassed(false);
            result.setScore(0);
            result.setReason("Unknown quality check error");
            return result;
        }
        result.setPassed(source.isPassed());
        result.setScore(source.getScore());
        result.setReason(source.getReason());
        return result;
    }

    // ========= Backward-compatible DTOs =========

    public static class AgentResult {
        private boolean success;
        private String message;
        private String error;
        private List<?> toolCalls;

        public static AgentResult success(String message) {
            AgentResult r = new AgentResult();
            r.success = true;
            r.message = message;
            return r;
        }

        public static AgentResult failure(String error) {
            AgentResult r = new AgentResult();
            r.success = false;
            r.error = error;
            return r;
        }

        public static AgentResult toolCallRequired(String message, List<?> toolCalls) {
            AgentResult r = new AgentResult();
            r.success = false;
            r.message = message;
            r.toolCalls = toolCalls;
            return r;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getError() {
            return error;
        }

        public List<?> getToolCalls() {
            return toolCalls;
        }
    }

    public static class IntentResult {
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

    public static class CheckResult {
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
