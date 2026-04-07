package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yizhaoqi.smartpai.entity.ChatContext;
import com.yizhaoqi.smartpai.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 聊天上下文服务
 * 负责管理多轮对话的上下文存储与检索
 */
@Service
public class ChatContextService {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatContextService.class);
    
    /**
     * Redis Key前缀
     */
    private static final String KEY_PREFIX = "chat:context:session:";
    
    /**
     * 上下文过期时间：30分钟
     */
    private static final Duration EXPIRE_DURATION = Duration.ofMinutes(30);
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public ChatContextService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // 禁用将日期写为时间戳，使用 ISO-8601 格式
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    /**
     * 获取或创建上下文
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 聊天上下文
     */
    public ChatContext getOrCreateContext(String sessionId, String userId) {
        ChatContext context = getContext(sessionId);
        if (context == null) {
            context = ChatContext.create(sessionId, userId);
            save(context);
            logger.info("创建新上下文: sessionId={}, userId={}", sessionId, userId);
        } else {
            logger.info("获取现有上下文: sessionId={}, 消息数={}", sessionId, 
                context.getMessages() != null ? context.getMessages().size() : 0);
        }
        return context;
    }
    
    /**
     * 获取上下文
     * @param sessionId 会话ID
     * @return 聊天上下文，不存在返回null
     */
    public ChatContext getContext(String sessionId) {
        String key = buildKey(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        
        if (json == null) {
            logger.info("上下文不存在: key={}, sessionId={}", key, sessionId);
            return null;
        }
        
        try {
            ChatContext context = objectMapper.readValue(json, ChatContext.class);
            logger.info("获取上下文成功: key={}, sessionId={}, 消息数={}", 
                key, sessionId, context.getMessages() != null ? context.getMessages().size() : 0);
            return context;
        } catch (JsonProcessingException e) {
            logger.error("解析上下文失败: key={}, sessionId={}, error={}, json={}", 
                key, sessionId, e.getMessage(), json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return null;
        }
    }
    
    /**
     * 保存上下文
     * @param context 聊天上下文
     */
    public void save(ChatContext context) {
        String key = buildKey(context.getSessionId());
        try {
            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(key, json, EXPIRE_DURATION);
            logger.info("保存上下文成功: key={}, sessionId={}, 消息数={}", 
                key, context.getSessionId(),
                context.getMessages() != null ? context.getMessages().size() : 0);
        } catch (JsonProcessingException e) {
            logger.error("序列化上下文失败: sessionId={}, error={}", 
                context.getSessionId(), e.getMessage());
        }
    }
    
    /**
     * 添加消息到上下文
     * @param sessionId 会话ID
     * @param message 聊天消息
     */
    public void addMessage(String sessionId, ChatMessage message) {
        ChatContext context = getContext(sessionId);
        if (context == null) {
            logger.warn("添加消息失败，上下文不存在: sessionId={}", sessionId);
            return;
        }
        context.addMessage(message);
        save(context);
        logger.debug("添加消息: sessionId={}, role={}", sessionId, message.getRole());
    }
    
    /**
     * 添加用户消息
     * @param sessionId 会话ID
     * @param content 消息内容
     */
    public void addUserMessage(String sessionId, String content) {
        addMessage(sessionId, ChatMessage.userMessage(content));
    }
    
    /**
     * 添加助手消息
     * @param sessionId 会话ID
     * @param content 消息内容
     */
    public void addAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, ChatMessage.assistantMessage(content));
    }
    
    /**
     * 添加一整轮对话（用户问+助手答）
     * @param sessionId 会话ID
     * @param userContent 用户消息
     * @param assistantContent 助手消息
     */
    public void addRound(String sessionId, String userContent, String assistantContent) {
        ChatContext context = getContext(sessionId);
        if (context == null) {
            logger.warn("添加对话轮次失败，上下文不存在: sessionId={}", sessionId);
            return;
        }
        int beforeCount = context.getMessages() != null ? context.getMessages().size() : 0;
        context.addMessage(ChatMessage.userMessage(userContent));
        context.addMessage(ChatMessage.assistantMessage(assistantContent));
        save(context);
        int afterCount = context.getMessages() != null ? context.getMessages().size() : 0;
        logger.info("添加对话轮次成功: sessionId={}, 消息数: {} -> {}", sessionId, beforeCount, afterCount);
    }
    
    /**
     * 获取最近N轮历史
     * @param sessionId 会话ID
     * @param rounds 轮数
     * @return 消息列表
     */
    public List<ChatMessage> getRecentHistory(String sessionId, int rounds) {
        ChatContext context = getContext(sessionId);
        if (context == null) {
            return List.of();
        }
        return context.getRecentHistory(rounds);
    }
    
    /**
     * 获取全部历史
     * @param sessionId 会话ID
     * @return 消息列表
     */
    public List<ChatMessage> getAllHistory(String sessionId) {
        ChatContext context = getContext(sessionId);
        if (context == null) {
            return List.of();
        }
        return context.getAllHistory();
    }
    
    /**
     * 清空上下文历史
     * @param sessionId 会话ID
     */
    public void clearHistory(String sessionId) {
        ChatContext context = getContext(sessionId);
        if (context != null) {
            context.clearHistory();
            save(context);
            logger.info("清空上下文历史: sessionId={}", sessionId);
        }
    }
    
    /**
     * 删除上下文
     * @param sessionId 会话ID
     */
    public void deleteContext(String sessionId) {
        String key = buildKey(sessionId);
        redisTemplate.delete(key);
        logger.info("删除上下文: sessionId={}", sessionId);
    }
    
    /**
     * 设置激活文档
     * @param sessionId 会话ID
     * @param docId 文档ID
     */
    public void setActiveDoc(String sessionId, String docId) {
        ChatContext context = getContext(sessionId);
        if (context != null) {
            context.setActiveDocId(docId);
            save(context);
            logger.debug("设置激活文档: sessionId={}, docId={}", sessionId, docId);
        }
    }
    
    /**
     * 获取激活文档
     * @param sessionId 会话ID
     * @return 文档ID
     */
    public String getActiveDoc(String sessionId) {
        ChatContext context = getContext(sessionId);
        return context != null ? context.getActiveDocId() : null;
    }
    
    /**
     * 构建Redis Key
     */
    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
