package com.yizhaoqi.smartpai.mcp.context;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MCP 上下文
 * 在整个 MCP 执行过程中传递的上下文信息
 */
@Data
public class McpContext {

    // ========== 会话信息 ==========

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private String userId;

    /** 用户原始消息 */
    private String userMessage;

    /** 时间戳 */
    private LocalDateTime timestamp = LocalDateTime.now();

    // ========== 多轮对话上下文 ==========

    /** 对话历史 */
    private List<ChatMessage> chatHistory = new ArrayList<>();

    /** 上下文记忆（Redis存储） */
    private Map<String, Object> memory = new HashMap<>();

    // ========== 意图识别结果 ==========

    /** 用户意图 */
    private String intent;

    /** 意图置信度 */
    private double confidence;

    /** 提取的关键词 */
    private List<String> keywords = new ArrayList<>();

    // ========== 技能执行结果 ==========

    /** 已调用的技能列表 */
    private List<String> calledSkills = new ArrayList<>();

    /** 技能执行结果缓存 */
    private Map<String, Object> skillResults = new HashMap<>();

    /** 最终回复 */
    private String finalReply;

    /** 来源列表 */
    private List<SourceInfo> sources = new ArrayList<>();

    // ========== 状态管理 ==========

    /** 当前状态 */
    private McpState state = McpState.INIT;

    /** 是否需要联网搜索 */
    private boolean needWebSearch = false;

    /** 是否通过质检 */
    private boolean checkPassed = false;

    /** 质检分数 */
    private int checkScore = 0;

    /** 重试次数 */
    private int retryCount = 0;

    /** 最大重试次数 */
    private int maxRetries = 2;

    /** 错误信息 */
    private String errorMessage;

    // ========== 配置 ==========

    /** 是否启用流式返回 */
    private boolean streamingEnabled = true;

    /** 是否启用联网搜索 */
    private boolean webSearchEnabled = true;

    /** 是否启用质检 */
    private boolean checkEnabled = true;

    /** 是否启用历史记忆 */
    private boolean memoryEnabled = true;

    // ========== 构造函数 ==========

    public McpContext() {}

    public McpContext(String sessionId, String userId, String userMessage) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userMessage = userMessage;
    }

    // ========== 便捷方法 ==========

    public void addChatMessage(String role, String content) {
        chatHistory.add(new ChatMessage(role, content));
    }

    public void addCalledSkill(String skillName) {
        calledSkills.add(skillName);
    }

    public void putSkillResult(String key, Object value) {
        skillResults.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getSkillResult(String key) {
        return (T) skillResults.get(key);
    }

    public void addSource(SourceInfo source) {
        sources.add(source);
    }

    public List<ChatMessage> getRecentHistory(int n) {
        if (chatHistory.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, chatHistory.size() - n);
        return new ArrayList<>(chatHistory.subList(start, chatHistory.size()));
    }

    // ========== 内部类 ==========

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
    public static class SourceInfo {
        private String id;
        private String fileName;
        private String content;
        private double score;

        public SourceInfo(String id, String fileName, String content, double score) {
            this.id = id;
            this.fileName = fileName;
            this.content = content;
            this.score = score;
        }
    }
}
