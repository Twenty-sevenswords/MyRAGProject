package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j Agent 服务
 * 提供基于 LangChain4j 的 Agent 能力，支持工具调用
 */
@Service
public class LangChain4jAgentService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jAgentService.class);

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;
    private final List<Object> tools = new ArrayList<>();
    private final Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();
    private final Map<String, Object> toolInstanceMap = new ConcurrentHashMap<>();

    public LangChain4jAgentService(ChatLanguageModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册工具
     */
    public void registerTool(Object tool) {
        tools.add(tool);
        logger.info("[LangChain4j Agent] 注册工具: {}", tool.getClass().getSimpleName());
        
        // 扫描并缓存工具方法
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                toolMethodMap.put(toolName, method);
                toolInstanceMap.put(toolName, tool);
                logger.info("[LangChain4j Agent] 注册工具方法: {} -> {}", toolName, method.getName());
            }
        }
    }

    /**
     * 执行 Agent 任务
     */
    public AgentResult execute(String userMessage) {
        return execute(null, userMessage, null);
    }

    /**
     * 执行 Agent 任务（带系统提示）
     */
    public AgentResult execute(String systemPrompt, String userMessage, ChatMemory memory) {
        logger.info("[LangChain4j Agent] 执行任务: {}", userMessage);
        
        // 创建或使用现有记忆
        ChatMemory chatMemory = memory != null ? memory : MessageWindowChatMemory.withMaxMessages(10);
        
        // 添加系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            chatMemory.add(SystemMessage.from(systemPrompt));
        }
        
        // 添加用户消息
        chatMemory.add(UserMessage.from(userMessage));
        
        // 获取工具规格
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        for (Object tool : tools) {
            toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        }
        
        try {
            // 生成响应
            Response<AiMessage> response;
            if (toolSpecs.isEmpty()) {
                response = chatModel.generate(chatMemory.messages());
            } else {
                response = chatModel.generate(chatMemory.messages(), toolSpecs);
            }
            
            AiMessage aiMessage = response.content();
            
            // 检查是否需要执行工具
            if (aiMessage.hasToolExecutionRequests()) {
                logger.info("[LangChain4j Agent] 需要执行工具: {} 个", 
                        aiMessage.toolExecutionRequests().size());
                
                // 执行工具调用
                return executeToolsAndContinue(aiMessage, chatMemory, toolSpecs);
            }
            
            // 添加 AI 响应到记忆
            chatMemory.add(aiMessage);
            
            return AgentResult.success(aiMessage.text());
            
        } catch (Exception e) {
            logger.error("[LangChain4j Agent] 执行失败", e);
            return AgentResult.failure(e.getMessage());
        }
    }

    /**
     * 执行工具调用并继续对话
     */
    private AgentResult executeToolsAndContinue(AiMessage aiMessage, ChatMemory chatMemory, 
                                                  List<ToolSpecification> toolSpecs) {
        try {
            // 添加 AI 消息到记忆
            chatMemory.add(aiMessage);
            
            // 执行所有工具调用
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                logger.info("[LangChain4j Agent] 执行工具: name={}, id={}", 
                        toolRequest.name(), toolRequest.id());
                
                String toolResult = executeToolCall(toolRequest);
                logger.info("[LangChain4j Agent] 工具执行结果: {}", 
                        toolResult.length() > 200 ? toolResult.substring(0, 200) + "..." : toolResult);
                
                // 添加工具执行结果到记忆
                chatMemory.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
            }
            
            // 继续生成响应
            Response<AiMessage> continueResponse = chatModel.generate(chatMemory.messages(), toolSpecs);
            AiMessage continueMessage = continueResponse.content();
            
            // 检查是否还需要执行工具（递归处理）
            if (continueMessage.hasToolExecutionRequests()) {
                logger.info("[LangChain4j Agent] 继续执行工具: {} 个", 
                        continueMessage.toolExecutionRequests().size());
                return executeToolsAndContinue(continueMessage, chatMemory, toolSpecs);
            }
            
            // 添加最终响应到记忆
            chatMemory.add(continueMessage);
            
            return AgentResult.success(continueMessage.text());
            
        } catch (Exception e) {
            logger.error("[LangChain4j Agent] 工具执行失败", e);
            return AgentResult.failure("工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行单个工具调用
     */
    private String executeToolCall(ToolExecutionRequest toolRequest) {
        String toolName = toolRequest.name();
        String argumentsJson = toolRequest.arguments();
        
        try {
            Method method = toolMethodMap.get(toolName);
            Object toolInstance = toolInstanceMap.get(toolName);
            
            if (method == null || toolInstance == null) {
                logger.warn("[LangChain4j Agent] 未找到工具: {}", toolName);
                return "{\"error\": \"Tool not found: " + toolName + "\"}";
            }
            
            // 解析参数
            Map<String, Object> arguments = objectMapper.readValue(argumentsJson, 
                    new TypeReference<Map<String, Object>>() {});
            
            // 准备方法参数
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            
            for (int i = 0; i < paramTypes.length; i++) {
                String paramName = method.getParameters()[i].getName();
                Object value = arguments.get(paramName);
                
                if (value != null) {
                    args[i] = convertArgument(value, paramTypes[i]);
                } else {
                    args[i] = getDefaultValue(paramTypes[i]);
                }
            }
            
            // 执行方法
            method.setAccessible(true);
            Object result = method.invoke(toolInstance, args);
            
            // 转换结果为字符串
            if (result == null) {
                return "null";
            } else if (result instanceof String) {
                return (String) result;
            } else {
                return objectMapper.writeValueAsString(result);
            }
            
        } catch (Exception e) {
            logger.error("[LangChain4j Agent] 工具调用异常: tool={}, error={}", 
                    toolName, e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    /**
     * 转换参数类型
     */
    private Object convertArgument(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        
        // 处理基本类型转换
        if (targetType == String.class) {
            return value.toString();
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(value.toString());
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(value.toString());
        } else if (targetType == Double.class || targetType == double.class) {
            return Double.valueOf(value.toString());
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.valueOf(value.toString());
        }
        
        // 复杂类型使用 Jackson 转换
        return objectMapper.convertValue(value, targetType);
    }

    /**
     * 获取参数默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return null;
    }

    /**
     * 意图识别（使用默认 prompt）
     */
    public IntentResult recognizeIntent(String userMessage) {
        String systemPrompt = """
                你是一个意图识别专家。请分析用户消息，识别其意图。
                
                可能的意图类型：
                - SEARCH: 搜索/查找信息
                - QA: 问答
                - COMPARE: 比较/对比
                - SUMMARIZE: 总结/摘要
                - CHAT: 普通聊天
                
                请以 JSON 格式返回结果：
                {"intent": "意图类型", "confidence": 0.0-1.0, "keywords": ["关键词1", "关键词2"]}
                """;
        
        return recognizeIntentWithPrompt(systemPrompt, userMessage);
    }
    
    /**
     * 意图识别（使用自定义 prompt template）
     * @param promptTemplate 自定义 prompt 模板，{message} 会被替换为用户消息
     * @param userMessage 用户消息
     * @return 意图识别结果
     */
    public IntentResult recognizeIntentWithPrompt(String promptTemplate, String userMessage) {
        logger.info("[LangChain4j Agent] 开始意图识别: {}", userMessage);
        
        try {
            // 替换模板中的占位符
            String prompt = promptTemplate.replace("{message}", userMessage);
            logger.debug("[LangChain4j Agent] 构建的Prompt:\n{}", prompt);
            
            // 调用 LLM
            String responseText = chatModel.generate(prompt);
            logger.info("[LangChain4j Agent] LLM 原始响应: {}", responseText);
            
            // 解析响应
            IntentResult result = parseIntentResult(responseText);
            logger.info("[LangChain4j Agent] 意图识别结果: intent={}, confidence={}, keywords={}",
                    result.getIntent(), result.getConfidence(), result.getKeywords());
            
            return result;
        } catch (Exception e) {
            logger.error("[LangChain4j Agent] 意图识别异常: {}", e.getMessage(), e);
            // 返回默认结果
            IntentResult fallback = new IntentResult();
            fallback.setIntent("UNKNOWN");
            fallback.setConfidence(0.3);
            fallback.setKeywords(List.of(userMessage.split("\\s+")));
            return fallback;
        }
    }

    private IntentResult parseIntentResult(String response) {
        IntentResult result = new IntentResult();
        
        try {
            // 提取 JSON 部分
            String json = extractJson(response);
            logger.debug("[LangChain4j Agent] 提取的JSON: {}", json);
            
            // 使用 ObjectMapper 解析
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = objectMapper.readValue(json, java.util.Map.class);
            
            // 解析 intent
            if (map.containsKey("intent")) {
                result.setIntent(map.get("intent").toString());
            } else {
                result.setIntent("CHAT");
            }
            
            // 解析 confidence
            if (map.containsKey("confidence")) {
                Object conf = map.get("confidence");
                if (conf instanceof Number) {
                    result.setConfidence(((Number) conf).doubleValue());
                } else {
                    result.setConfidence(Double.parseDouble(conf.toString()));
                }
            } else {
                result.setConfidence(0.85);
            }
            
            // 解析 keywords
            if (map.containsKey("keywords") && map.get("keywords") instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) map.get("keywords");
                result.setKeywords(keywords);
            }
            
            // 解析 complexity
            if (map.containsKey("complexity")) {
                Object comp = map.get("complexity");
                if (comp instanceof Number) {
                    result.setComplexity(((Number) comp).intValue());
                } else {
                    result.setComplexity(Integer.parseInt(comp.toString()));
                }
            }
            
            // 解析 needsHistory
            if (map.containsKey("needsHistory")) {
                result.setNeedsHistory(Boolean.parseBoolean(map.get("needsHistory").toString()));
            }
            
            logger.info("[LangChain4j Agent] JSON解析成功");
            return result;
            
        } catch (Exception e) {
            logger.warn("[LangChain4j Agent] JSON解析失败，使用简单解析: {}", e.getMessage());
            // 降级：简单解析
            if (response.contains("SEARCH")) {
                result.setIntent("SEARCH");
            } else if (response.contains("COMPARE")) {
                result.setIntent("COMPARE");
            } else if (response.contains("SUMMARIZE")) {
                result.setIntent("SUMMARIZE");
            } else if (response.contains("QA")) {
                result.setIntent("QA");
            } else {
                result.setIntent("CHAT");
            }
            result.setConfidence(0.85);
            return result;
        }
    }
    
    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 质量检查
     */
    public CheckResult checkQuality(String question, String answer) {
        String systemPrompt = """
                你是一个质量检查专家。请判断以下回答是否很好地回答了用户的问题。
                
                检查标准：
                1. 回答是否与问题相关
                2. 回答是否完整
                3. 回答是否准确
                
                请以 JSON 格式返回结果：
                {"passed": true/false, "score": 0-100, "reason": "原因"}
                """;
        
        String userPrompt = String.format("问题：%s\n回答：%s", question, answer);
        
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userPrompt));
        
        Response<AiMessage> response = chatModel.generate(messages);
        String responseText = response.content().text();
        
        CheckResult result = new CheckResult();
        result.setPassed(responseText.contains("true") || responseText.contains("passed"));
        result.setScore(extractScore(responseText));
        result.setReason(responseText);
        
        return result;
    }

    private int extractScore(String response) {
        try {
            int start = response.indexOf("score");
            if (start > 0) {
                int colon = response.indexOf(":", start);
                int end = response.indexOf(",", colon);
                if (end < 0) end = response.indexOf("}", colon);
                return Integer.parseInt(response.substring(colon + 1, end).trim());
            }
        } catch (Exception e) {
            // ignore
        }
        return 70;
    }

    // ========== 内部类 ==========

    /**
     * Agent 执行结果
     */
    public static class AgentResult {
        private boolean success;
        private String message;
        private String error;
        private List<?> toolCalls;

        public static AgentResult success(String message) {
            AgentResult r = new AgentResult();
            r.success = true;
            r.message = message;
            return r;
        }

        public static AgentResult failure(String error) {
            AgentResult r = new AgentResult();
            r.success = false;
            r.error = error;
            return r;
        }

        public static AgentResult toolCallRequired(String message, List<?> toolCalls) {
            AgentResult r = new AgentResult();
            r.success = false;
            r.message = message;
            r.toolCalls = toolCalls;
            return r;
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getError() { return error; }
        public List<?> getToolCalls() { return toolCalls; }
    }

    /**
     * 意图识别结果
     */
    public static class IntentResult {
        private String intent;
        private double confidence;
        private List<String> keywords = new ArrayList<>();
        private int complexity;
        private boolean needsHistory;

        // Getters and Setters
        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
        public int getComplexity() { return complexity; }
        public void setComplexity(int complexity) { this.complexity = complexity; }
        public boolean isNeedsHistory() { return needsHistory; }
        public void setNeedsHistory(boolean needsHistory) { this.needsHistory = needsHistory; }
    }

    /**
     * 质量检查结果
     */
    public static class CheckResult {
        private boolean passed;
        private int score;
        private String reason;

        // Getters and Setters
        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
