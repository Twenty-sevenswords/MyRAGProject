package com.yizhaoqi.smartpai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天上下文实体
 * 存储会话级别的对话上下文
 * 
 * Redis Key: chat:context:session:{sessionId}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatContext {
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 最近N轮消息列表（每轮包含用户问+助手答）
     */
    private List<ChatMessage> messages;
    
    /**
     * 当前激活文档ID
     */
    private String activeDocId;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 最大保留轮数（1轮=用户问+助手答=2条消息）
     */
    public static final int MAX_ROUNDS = 10;
    
    /**
     * 创建新的上下文
     */
    public static ChatContext create(String sessionId, String userId) {
        ChatContext context = new ChatContext();
        context.setSessionId(sessionId);
        context.setUserId(userId);
        context.setMessages(new ArrayList<>());
        context.setCreatedAt(LocalDateTime.now());
        context.setUpdatedAt(LocalDateTime.now());
        return context;
    }
    
    /**
     * 添加消息（自动滑动窗口）
     */
    public void addMessage(ChatMessage message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
        this.updatedAt = LocalDateTime.now();
        
        // 滑动窗口：保留最近10轮（20条消息）
        int maxMessages = MAX_ROUNDS * 2;
        if (this.messages.size() > maxMessages) {
            this.messages = new ArrayList<>(
                this.messages.subList(this.messages.size() - maxMessages, this.messages.size())
            );
        }
    }
    
    /**
     * 获取最近N轮历史（用于构建Prompt）
     * @param rounds 轮数
     * @return 消息列表
     */
    public List<ChatMessage> getRecentHistory(int rounds) {
        if (this.messages == null || this.messages.isEmpty()) {
            return new ArrayList<>();
        }
        int count = rounds * 2;
        if (this.messages.size() <= count) {
            return new ArrayList<>(this.messages);
        }
        return new ArrayList<>(
            this.messages.subList(this.messages.size() - count, this.messages.size())
        );
    }
    
    /**
     * 获取全部历史
     */
    public List<ChatMessage> getAllHistory() {
        return this.messages == null ? new ArrayList<>() : new ArrayList<>(this.messages);
    }
    
    /**
     * 清空历史
     */
    public void clearHistory() {
        if (this.messages != null) {
            this.messages.clear();
        }
        this.updatedAt = LocalDateTime.now();
    }
}
