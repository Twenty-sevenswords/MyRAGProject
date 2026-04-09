package com.yizhaoqi.smartpai.mcp.skill;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 技能参数
 */
@Data
public class SkillParams {

    private Map<String, Object> params = new HashMap<>();

    public static SkillParams create() {
        return new SkillParams();
    }

    public SkillParams put(String key, Object value) {
        params.put(key, value);
        return this;
    }

    public SkillParams putAll(Map<String, Object> map) {
        if (map != null) {
            params.putAll(map);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) params.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = params.get(key);
        return value != null ? (T) value : defaultValue;
    }

    public String getString(String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    public Integer getInteger(String key) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    public Integer getInteger(String key, Integer defaultValue) {
        Integer value = getInteger(key);
        return value != null ? value : defaultValue;
    }

    public Boolean getBoolean(String key) {
        Object value = params.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        Boolean value = getBoolean(key);
        return value != null ? value : defaultValue;
    }

    public static SkillParams fromMap(Map<String, Object> map) {
        SkillParams sp = new SkillParams();
        if (map != null) {
            sp.params.putAll(map);
        }
        return sp;
    }
}
