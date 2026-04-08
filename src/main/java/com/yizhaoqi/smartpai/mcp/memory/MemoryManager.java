package com.yizhaoqi.smartpai.mcp.memory;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.repository.RedisRepository;
import com.yizhaoqi.smartpai.service.QaMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 记忆管理器
 * 负责多轮上下文记忆和历史问答缓存
 */
@Component
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    @Autowired
    private RedisRepository redisRepository;

    @Autowired
    private QaMemoryService qaMemoryService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 对话历史过期时间（小时） */
    private static final long HISTORY_EXPIRE_HOURS = 24;

    /** 问答缓存过期时间（天） */
    private static final long QA_CACHE_EXPIRE_DAYS = 7;

    // ========== 会话记忆 ==========

    /**
     * 加载会话记忆
     */
    public void loadMemory(McpContext context) {
        logger.info("[Memory] 开始加载会话记忆, sessionId={}", context.getSessionId());
        try {
            // 加载对话历史
            logger.debug("[Memory] 正在加载对话历史...");
            List<Map<String, Object>> history = loadChatHistory(context.getSessionId());
            for (Map<String, Object> msg : history) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                if (role != null && content != null) {
                    context.addChatMessage(role, content);
                }
            }
            logger.info("[Memory] 加载对话历史: {} 条", history.size());

            // 加载上下文变量
            logger.debug("[Memory] 正在加载上下文变量...");
            Map<String, Object> memory = loadContextMemory(context.getSessionId());
            context.getMemory().putAll(memory);
            logger.info("[Memory] 加载上下文变量: {} 个", memory.size());
            
            logger.info("[Memory] ✅ 会话记忆加载完成");

        } catch (Exception e) {
            logger.error("[Memory] ❌ 加载记忆失败: {}", e.getMessage(), e);
            // 不抛出异常，允许继续执行
        }
    }

    /**
     * 保存会话记忆
     */
    public void saveMemory(McpContext context) {
        try {
            // 保存对话历史
            saveChatHistory(context.getSessionId(), context.getChatHistory());

            // 保存问答缓存
            if (context.getFinalReply() != null && context.isCheckPassed()) {
                saveQaCache(context.getUserMessage(), context.getUserId(), 
                        context.getFinalReply(), context.getIntent());
            }

            logger.debug("[Memory] 保存记忆完成");

        } catch (Exception e) {
            logger.warn("[Memory] 保存记忆失败: {}", e.getMessage());
        }
    }

    /**
     * 获取缓存的答案
     */
    public Object getCachedAnswer(String question, String userId) {
        try {
            String key = buildQaCacheKey(question, userId);
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.warn("[Memory] 获取缓存答案失败: {}", e.getMessage());
            return null;
        }
    }

    // ========== 私有方法 ==========

    private String buildHistoryKey(String sessionId) {
        return "mcp:history:" + sessionId;
    }

    private String buildMemoryKey(String sessionId) {
        return "mcp:memory:" + sessionId;
    }

    private String buildQaCacheKey(String question, String userId) {
        return "mcp:qa_cache:" + userId + ":" + question.hashCode();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadChatHistory(String sessionId) {
        try {
            String key = buildHistoryKey(sessionId);
            Object data = redisTemplate.opsForValue().get(key);
            if (data instanceof List) {
                return (List<Map<String, Object>>) data;
            }
        } catch (Exception e) {
            logger.debug("[Memory] 加载对话历史失败: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    private void saveChatHistory(String sessionId, List<McpContext.ChatMessage> history) {
        try {
            String key = buildHistoryKey(sessionId);
            List<Map<String, Object>> data = new ArrayList<>();
            for (McpContext.ChatMessage msg : history) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", msg.getRole());
                item.put("content", msg.getContent());
                item.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toString() : null);
                data.add(item);
            }
            redisTemplate.opsForValue().set(key, data, HISTORY_EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.warn("[Memory] 保存对话历史失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadContextMemory(String sessionId) {
        try {
            String key = buildMemoryKey(sessionId);
            Object data = redisTemplate.opsForValue().get(key);
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
        } catch (Exception e) {
            logger.debug("[Memory] 加载上下文变量失败: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    private void saveQaCache(String question, String userId, String answer, String intent) {
        try {
            String key = buildQaCacheKey(question, userId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("question", question);
            data.put("answer", answer);
            data.put("intent", intent);
            data.put("timestamp", System.currentTimeMillis());
            redisTemplate.opsForValue().set(key, data, QA_CACHE_EXPIRE_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            logger.warn("[Memory] 保存问答缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 清除会话记忆
     */
    public void clearMemory(String sessionId) {
        try {
            redisTemplate.delete(buildHistoryKey(sessionId));
            redisTemplate.delete(buildMemoryKey(sessionId));
            logger.info("[Memory] 清除会话记忆: {}", sessionId);
        } catch (Exception e) {
            logger.warn("[Memory] 清除记忆失败: {}", e.getMessage());
        }
    }
}
