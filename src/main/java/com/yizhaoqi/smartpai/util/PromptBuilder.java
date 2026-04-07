package com.yizhaoqi.smartpai.util;

import com.yizhaoqi.smartpai.entity.ChatMessage;
import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prompt构建工具类
 * 负责拼接：系统提示词 + 历史上下文 + RAG检索片段 + 当前用户问题
 */
public class PromptBuilder {
    
    /**
     * 默认系统提示词
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是一个智能问答助手，专注于根据提供的参考资料回答用户问题。
        请遵循以下规则：
        1. 优先基于参考资料回答，如果资料中没有相关信息，请诚实说明
        2. 保持回答简洁、准确、专业
        3. 如果用户问题涉及之前的对话内容，请结合上下文理解
        4. 对于省略、指代（如"它"、"这个"、"为什么"、"然后呢"），请根据历史上下文推断用户意图
        """;
    
    /**
     * 参考资料标记
     */
    private static final String REF_START = "【参考资料】";
    private static final String REF_END = "【参考资料结束】";
    
    /**
     * 历史对话标记
     */
    private static final String HISTORY_START = "【历史对话】";
    private static final String HISTORY_END = "【历史对话结束】";
    
    /**
     * 每段最大长度
     */
    private static final int MAX_SNIPPET_LEN = 300;
    
    /**
     * 构建完整的Prompt
     * @param systemPrompt 系统提示词（可为null，使用默认）
     * @param history 历史消息列表
     * @param searchResults RAG检索结果
     * @param userQuestion 用户问题
     * @return 构建的消息列表（可直接传给LLM API）
     */
    public static List<Map<String, String>> buildMessages(
            String systemPrompt,
            List<ChatMessage> history,
            List<SearchResult> searchResults,
            String userQuestion) {
        
        java.util.ArrayList<Map<String, String>> messages = new java.util.ArrayList<>();
        
        // 1. 系统提示词 + 参考资料
        StringBuilder systemBuilder = new StringBuilder();
        systemBuilder.append(systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT);
        systemBuilder.append("\n\n");
        
        // 添加参考资料
        String context = buildContextFromSearchResults(searchResults);
        if (!context.isEmpty()) {
            systemBuilder.append(REF_START).append("\n");
            systemBuilder.append(context);
            systemBuilder.append(REF_END).append("\n");
        } else {
            systemBuilder.append("（本轮无相关参考资料）\n");
        }
        
        messages.add(Map.of("role", "system", "content", systemBuilder.toString()));
        
        // 2. 历史对话
        if (history != null && !history.isEmpty()) {
            for (ChatMessage msg : history) {
                messages.add(Map.of(
                    "role", msg.getRole(),
                    "content", msg.getContent()
                ));
            }
        }
        
        // 3. 当前用户问题
        messages.add(Map.of("role", "user", "content", userQuestion));
        
        return messages;
    }
    
    /**
     * 构建简化的历史消息格式（用于DeepSeekClient）
     * @param history 历史消息列表
     * @return Map格式的消息列表
     */
    public static List<Map<String, String>> buildHistoryMessages(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        
        return history.stream()
            .map(msg -> Map.of(
                "role", msg.getRole(),
                "content", msg.getContent()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * 从检索结果构建上下文字符串
     * @param searchResults 检索结果列表
     * @return 格式化的上下文字符串
     */
    public static String buildContextFromSearchResults(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return "";
        }
        
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            
            if (snippet == null || snippet.isEmpty()) {
                continue;
            }
            
            // 截断过长内容
            if (snippet.length() > MAX_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_SNIPPET_LEN) + "…";
            }
            
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
        }
        
        return context.toString();
    }
    
    /**
     * 构建历史对话摘要字符串（用于调试或显示）
     * @param history 历史消息列表
     * @return 格式化的历史字符串
     */
    public static String buildHistorySummary(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(HISTORY_START).append("\n");
        
        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String roleLabel = "user".equals(msg.getRole()) ? "用户" : 
                              "assistant".equals(msg.getRole()) ? "助手" : "系统";
            sb.append(String.format("[%d] %s: %s\n", i + 1, roleLabel, 
                truncate(msg.getContent(), 100)));
        }
        
        sb.append(HISTORY_END);
        return sb.toString();
    }
    
    /**
     * 截断字符串
     */
    private static String truncate(String str, int maxLen) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen) + "…";
    }
    
    /**
     * 构建带上下文的用户问题
     * 用于需要将历史和当前问题合并的场景
     * @param history 历史消息
     * @param userQuestion 当前问题
     * @return 合并后的问题
     */
    public static String buildContextualQuestion(List<ChatMessage> history, String userQuestion) {
        if (history == null || history.isEmpty()) {
            return userQuestion;
        }
        
        // 检查是否包含指代词
        if (containsReference(userQuestion)) {
            // 尝试找到最近的相关上下文
            ChatMessage lastUserMsg = findLastUserMessage(history);
            if (lastUserMsg != null) {
                return String.format("（关于之前的问题：%s）\n当前问题：%s", 
                    lastUserMsg.getContent(), userQuestion);
            }
        }
        
        return userQuestion;
    }
    
    /**
     * 检查是否包含指代词
     */
    private static boolean containsReference(String question) {
        if (question == null) {
            return false;
        }
        String[] references = {"它", "这个", "那个", "为什么", "然后呢", "接着", "继续", 
            "上面", "刚才", "之前的", "刚才说的"};
        String lowerQuestion = question.toLowerCase();
        for (String ref : references) {
            if (lowerQuestion.contains(ref)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 找到最近一条用户消息
     */
    private static ChatMessage findLastUserMessage(List<ChatMessage> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                return history.get(i);
            }
        }
        return null;
    }
}
