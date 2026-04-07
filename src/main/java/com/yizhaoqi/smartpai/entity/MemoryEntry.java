package com.yizhaoqi.smartpai.entity;

import java.time.LocalDateTime;

public class MemoryEntry {
    private String id;
    private String sessionId;
    private String key;
    private Object value;
    private LocalDateTime timestamp;
    private int accessCount;

    // getter/setter
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }
}