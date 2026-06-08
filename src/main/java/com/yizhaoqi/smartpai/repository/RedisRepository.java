package com.yizhaoqi.smartpai.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.MemoryEntry;
import com.yizhaoqi.smartpai.dto.Message;
import com.yizhaoqi.smartpai.dto.QaMemoryEntry;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Repository
public class RedisRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRepository(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String getCurrentConversationId(String userId) {
        return (String) redisTemplate.opsForValue().get("user:" + userId + ":current_conversation");
    }

    public List<Message> getConversationHistory(String conversationId) {
        String json = (String) redisTemplate.opsForValue().get("conversation:" + conversationId);
        try {
            return json == null ? new ArrayList<>() : objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Message.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse conversation history", e);
        }
    }

    public void saveConversationHistory(String conversationId, List<Message> messages) throws JsonProcessingException {
        redisTemplate.opsForValue().set("conversation:" + conversationId, objectMapper.writeValueAsString(messages), Duration.ofDays(7));
    }

    // ========== 新增：Agent记忆方法 ==========

    /**
     * 通用获取方法
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 通用设置方法（带过期时间）
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, Duration.ofMillis(unit.toMillis(timeout)));
    }

    /**
     * 通用删除方法
     */
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public void saveMemory(MemoryEntry entry) {
        String key = String.format("pai:memory:%s:%s",
                entry.getSessionId(), entry.getKey());

        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(1));
        } catch (Exception e) {
            throw new RuntimeException("保存记忆失败", e);
        }
    }

    public <T> T getMemory(String sessionId, String key, Class<T> clazz) {
        String redisKey = String.format("pai:memory:%s:%s", sessionId, key);
        String json = (String) redisTemplate.opsForValue().get(redisKey);

        if (json == null) return null;

        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    public List<MemoryEntry> getSessionMemories(String sessionId) {
        String pattern = String.format("pai:memory:%s:*", sessionId);

        return redisTemplate.keys(pattern).stream()
                .map(key -> (String) redisTemplate.opsForValue().get(key))
                .map(this::parseMemory)
                .collect(Collectors.toList());
    }

    private MemoryEntry parseMemory(String json) {
        try {
            return objectMapper.readValue(json, MemoryEntry.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 问答记忆方法 ==========

    /**
     * 保存问答记忆
     */
    public void saveQaMemory(QaMemoryEntry entry, String keyPrefix, int expireDays) {
        String key = String.format("%s:user:%s:qa:%s",
                keyPrefix, entry.getUserId(), entry.getId());

        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(expireDays));
        } catch (Exception e) {
            throw new RuntimeException("保存问答记忆失败", e);
        }
    }

    /**
     * 获取用户的所有问答记忆
     */
    public List<QaMemoryEntry> getUserQaMemories(String userId, String keyPrefix) {
        String pattern = String.format("%s:user:%s:qa:*", keyPrefix, userId);

        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }

        return keys.stream()
                .map(key -> (String) redisTemplate.opsForValue().get(key))
                .filter(json -> json != null)
                .map(this::parseQaMemory)
                .filter(entry -> entry != null && !entry.isExpired())
                .collect(Collectors.toList());
    }

    /**
     * 根据问题哈希查找记忆
     */
    public QaMemoryEntry findByQuestionHash(String userId, String questionHash, String keyPrefix) {
        List<QaMemoryEntry> memories = getUserQaMemories(userId, keyPrefix);
        return memories.stream()
                .filter(entry -> questionHash.equals(entry.getQuestionHash()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 更新问答记忆（访问次数等）
     */
    public void updateQaMemory(QaMemoryEntry entry, String keyPrefix, int expireDays) {
        saveQaMemory(entry, keyPrefix, expireDays);
    }

    /**
     * 按记忆ID删除问答记忆
     */
    public void deleteQaMemory(String userId, String memoryId, String keyPrefix) {
        String key = String.format("%s:user:%s:qa:%s", keyPrefix, userId, memoryId);
        redisTemplate.delete(key);
    }

    /**
     * 删除过期的问答记忆
     */
    public void cleanExpiredQaMemories(String userId, String keyPrefix) {
        String pattern = String.format("%s:user:%s:qa:*", keyPrefix, userId);

        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return;
        }

        keys.stream()
                .map(key -> (String) redisTemplate.opsForValue().get(key))
                .filter(json -> json != null)
                .map(this::parseQaMemory)
                .filter(entry -> entry != null && entry.isExpired())
                .forEach(entry -> {
                    String key = String.format("%s:user:%s:qa:%s",
                            keyPrefix, userId, entry.getId());
                    redisTemplate.delete(key);
                });
    }

    /**
     * 获取用户问答记忆数量
     */
    public long getQaMemoryCount(String userId, String keyPrefix) {
        String pattern = String.format("%s:user:%s:qa:*", keyPrefix, userId);
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null ? 0 : keys.size();
    }

    private QaMemoryEntry parseQaMemory(String json) {
        try {
            return objectMapper.readValue(json, QaMemoryEntry.class);
        } catch (Exception e) {
            return null;
        }
    }
}
