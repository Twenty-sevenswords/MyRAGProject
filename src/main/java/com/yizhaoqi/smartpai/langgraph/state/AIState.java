package com.yizhaoqi.smartpai.langgraph.state;

import com.yizhaoqi.smartpai.dto.AgentIntent;
import com.yizhaoqi.smartpai.dto.AgentResult;
import com.yizhaoqi.smartpai.dto.SearchResult;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core state object flowing through LangGraph nodes.
 */
@Data
public class AIState {

    // Basic info
    private String sessionId;
    private String userId;
    private String currentMessage;
    private LocalDateTime timestamp;
    private String traceId;

    // Multi-turn context
    private List<ChatMessage> chatHistory = new ArrayList<>();
    private Map<String, Object> contextMemory = new HashMap<>();

    // Router output
    private AgentIntent intent;
    private String routeDecision;

    // Action output
    private List<SearchResult> searchResults = new ArrayList<>();
    private String generatedReply;
    private boolean usedLLM;
    private int llmCost;
    private List<?> sources = new ArrayList<>();

    // Query analysis output
    private String rewrittenQuery;
    private boolean needsWebSearch;

    // Grading output
    private List<SearchResult> gradedResults = new ArrayList<>();
    private boolean sufficientContext;

    // Hallucination check output
    private boolean hallucinationPassed;
    private int hallucinationScore;

    // Final check output
    private boolean checkPassed;
    private int checkScore;
    private String checkReason;
    private boolean needsRetry;
    private int retryCount = 0;

    // Final output
    private String finalReply;
    private boolean cacheHit;
    private String cachedQuestion;

    // Metadata
    private List<String> executionPath = new ArrayList<>();
    private Map<String, NodeResult> nodeResults = new HashMap<>();
    private Map<String, Long> nodeTimings = new HashMap<>();
    private String errorMessage;
    private boolean completed = false;

    public AIState() {
        this.timestamp = LocalDateTime.now();
        this.traceId = UUID.randomUUID().toString();
    }

    public AIState(String sessionId, String userId, String message) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.currentMessage = message;
        this.timestamp = LocalDateTime.now();
        this.traceId = UUID.randomUUID().toString();
    }

    public void addChatMessage(String role, String content) {
        chatHistory.add(new ChatMessage(role, content));
    }

    public void recordNodeExecution(String nodeName, String status, Object data) {
        executionPath.add(nodeName);
        nodeResults.put(nodeName, new NodeResult(nodeName, status, data, LocalDateTime.now()));
    }

    public void recordNodeTiming(String nodeName, long durationMs) {
        nodeTimings.put(nodeName, durationMs);
    }

    public List<ChatMessage> getRecentHistory(int n) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return new ArrayList<>();
        }
        int start = Math.max(0, chatHistory.size() - n);
        return chatHistory.subList(start, chatHistory.size());
    }

    public List<Map<String, String>> formatHistoryForLLM() {
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : chatHistory) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }
        return messages;
    }

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
        private LocalDateTime timestamp;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = LocalDateTime.now();
        }
    }

    @Data
    public static class NodeResult {
        private String nodeName;
        private String status;
        private Object data;
        private LocalDateTime timestamp;

        public NodeResult(String nodeName, String status, Object data, LocalDateTime timestamp) {
            this.nodeName = nodeName;
            this.status = status;
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}
