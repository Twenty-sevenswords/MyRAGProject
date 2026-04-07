package com.yizhaoqi.smartpai.langgraph.state;

import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.entity.SearchResult;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI状态类 - LangGraph核心状态定义
 * 在整个图执行过程中传递和累积状态
 */
@Data
public class AIState {

    // ========== 基础信息 ==========
    
    /** 会话ID */
    private String sessionId;
    
    /** 用户ID */
    private String userId;
    
    /** 当前用户消息 */
    private String currentMessage;
    
    /** 时间戳 */
    private LocalDateTime timestamp;

    // ========== 多轮对话上下文 ==========
    
    /** 对话历史（消息列表） */
    private List<ChatMessage> chatHistory = new ArrayList<>();
    
    /** 上下文记忆（Redis存储的key-value） */
    private Map<String, Object> contextMemory = new HashMap<>();

    // ========== Router节点输出 ==========
    
    /** 意图识别结果 */
    private AgentIntent intent;
    
    /** 路由决策：action | fallback | end */
    private String routeDecision;

    // ========== Action节点输出 ==========
    
    /** RAG检索结果 */
    private List<SearchResult> searchResults = new ArrayList<>();
    
    /** 生成的回复 */
    private String generatedReply;
    
    /** 是否使用了LLM */
    private boolean usedLLM;
    
    /** LLM调用成本 */
    private int llmCost;
    
    /** 来源文档列表 */
    private List<?> sources = new ArrayList<>();

    // ========== Check节点输出 ==========
    
    /** 检查是否通过 */
    private boolean checkPassed;
    
    /** 检查分数 */
    private int checkScore;
    
    /** 检查原因 */
    private String checkReason;
    
    /** 是否需要重试 */
    private boolean needsRetry;
    
    /** 重试次数 */
    private int retryCount = 0;

    // ========== 最终输出 ==========
    
    /** 最终回复 */
    private String finalReply;
    
    /** 是否命中缓存 */
    private boolean cacheHit;
    
    /** 缓存来源问题 */
    private String cachedQuestion;

    // ========== 元数据 ==========
    
    /** 执行轨迹（记录经过的节点） */
    private List<String> executionPath = new ArrayList<>();
    
    /** 节点执行结果（每个节点的输出） */
    private Map<String, NodeResult> nodeResults = new HashMap<>();
    
    /** 错误信息 */
    private String errorMessage;
    
    /** 是否已完成 */
    private boolean completed = false;

    // ========== 构造函数 ==========
    
    public AIState() {
        this.timestamp = LocalDateTime.now();
    }
    
    public AIState(String sessionId, String userId, String message) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.currentMessage = message;
        this.timestamp = LocalDateTime.now();
    }

    // ========== 便捷方法 ==========

    /**
     * 添加聊天历史
     */
    public void addChatMessage(String role, String content) {
        chatHistory.add(new ChatMessage(role, content));
    }

    /**
     * 记录节点执行
     */
    public void recordNodeExecution(String nodeName, String status, Object data) {
        executionPath.add(nodeName);
        nodeResults.put(nodeName, new NodeResult(nodeName, status, data, LocalDateTime.now()));
    }

    /**
     * 获取最后N轮对话
     */
    public List<ChatMessage> getRecentHistory(int n) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return new ArrayList<>();
        }
        int start = Math.max(0, chatHistory.size() - n);
        return chatHistory.subList(start, chatHistory.size());
    }

    /**
     * 格式化对话历史为LLM消息格式
     */
    public List<Map<String, String>> formatHistoryForLLM() {
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : chatHistory) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }
        return messages;
    }

    // ========== 内部类 ==========

    /**
     * 聊天消息
     */
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

    /**
     * 节点执行结果
     */
    @Data
    public static class NodeResult {
        private String nodeName;
        private String status;  // success, failed, skipped
        private Object data;
        private LocalDateTime timestamp;

        public NodeResult(String nodeName, String status, Object data, LocalDateTime timestamp) {
            this.nodeName = nodeName;
            this.status = status;
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}
