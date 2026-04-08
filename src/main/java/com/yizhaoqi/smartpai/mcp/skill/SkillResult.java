package com.yizhaoqi.smartpai.mcp.skill;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 技能执行结果
 */
@Data
public class SkillResult {

    private boolean success;
    private Object data;
    private String message;
    private String error;
    private Map<String, Object> metadata = new HashMap<>();
    private LocalDateTime timestamp = LocalDateTime.now();
    private long executionTimeMs;

    public static SkillResult success(Object data) {
        SkillResult result = new SkillResult();
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    public static SkillResult success(Object data, String message) {
        SkillResult result = success(data);
        result.setMessage(message);
        return result;
    }

    public static SkillResult failure(String error) {
        SkillResult result = new SkillResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    public static SkillResult failure(String error, String message) {
        SkillResult result = failure(error);
        result.setMessage(message);
        return result;
    }

    public SkillResult addMetadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getData() {
        return (T) data;
    }
}
