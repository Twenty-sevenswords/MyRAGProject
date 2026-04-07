package com.yizhaoqi.smartpai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 * 存储单条对话消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    
    /**
     * 角色：user / assistant / system
     */
    private String role;
    
    /**
     * 消息内容
     */
    private String content;
    
    /**
     * 时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 关联文档ID（可选，RAG检索时关联的文档）
     */
    private String relatedDocId;
    
    /**
     * 创建用户消息
     */
    public static ChatMessage userMessage(String content) {
        return new ChatMessage("user", content, LocalDateTime.now(), null);
    }
    
    /**
     * 创建用户消息（带文档关联）
     */
    public static ChatMessage userMessage(String content, String relatedDocId) {
        return new ChatMessage("user", content, LocalDateTime.now(), relatedDocId);
    }
    
    /**
     * 创建助手消息
     */
    public static ChatMessage assistantMessage(String content) {
        return new ChatMessage("assistant", content, LocalDateTime.now(), null);
    }
    
    /**
     * 创建系统消息
     */
    public static ChatMessage systemMessage(String content) {
        return new ChatMessage("system", content, LocalDateTime.now(), null);
    }
}
