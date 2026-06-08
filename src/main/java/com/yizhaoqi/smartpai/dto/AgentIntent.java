package com.yizhaoqi.smartpai.dto;

import java.util.List;
import java.util.Map;

public class AgentIntent {
    private String intent;           // SEARCH|COMPARE|SUMMARIZE|CHAT|UNKNOWN
    private List<String> keywords;
    private Map<String, String> entities;
    private int complexity;          // 1-5
    private boolean needsHistory;
    private double confidence;

    // 运行时字段
    private String sessionId;
    private String rawMessage;
    private String fallbackReason;   // 降级原因

    // 工厂方法：未知意图
    public static AgentIntent unknown(String message, String sessionId, String reason) {
        AgentIntent i = new AgentIntent();
        i.setIntent("UNKNOWN");
        i.setKeywords(List.of(message.split("\\s+")));
        i.setConfidence(0.3);
        i.setSessionId(sessionId);
        i.setRawMessage(message);
        i.setFallbackReason(reason);
        return i;
    }

    // getter/setter
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }
    public Map<String, String> getEntities() { return entities; }
    public void setEntities(Map<String, String> entities) { this.entities = entities; }
    public int getComplexity() { return complexity; }
    public void setComplexity(int complexity) { this.complexity = complexity; }
    public boolean isNeedsHistory() { return needsHistory; }
    public void setNeedsHistory(boolean needsHistory) { this.needsHistory = needsHistory; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRawMessage() { return rawMessage; }
    public void setRawMessage(String rawMessage) { this.rawMessage = rawMessage; }
    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String fallbackReason) { this.fallbackReason = fallbackReason; }
}