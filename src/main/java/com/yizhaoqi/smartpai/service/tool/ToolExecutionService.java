package com.yizhaoqi.smartpai.service.tool;

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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates tool registration and tool-call execution loop for LangChain4j agents.
 */
@Service
public class ToolExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper;
    private final List<Object> tools = new ArrayList<>();
    private final Map<String, Method> toolMethodMap = new ConcurrentHashMap<>();
    private final Map<String, Object> toolInstanceMap = new ConcurrentHashMap<>();

    public ToolExecutionService(ChatLanguageModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public void registerTool(Object tool) {
        tools.add(tool);
        logger.info("[ToolExecutionService] Register tool: {}", tool.getClass().getSimpleName());

        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool annotation = method.getAnnotation(Tool.class);
                String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                toolMethodMap.put(toolName, method);
                toolInstanceMap.put(toolName, tool);
            }
        }
    }

    public AgentExecutionResult execute(String userMessage) {
        return execute(null, userMessage, null);
    }

    public AgentExecutionResult execute(String systemPrompt, String userMessage, ChatMemory memory) {
        ChatMemory chatMemory = memory != null ? memory : MessageWindowChatMemory.withMaxMessages(10);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            chatMemory.add(SystemMessage.from(systemPrompt));
        }
        chatMemory.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = new ArrayList<>();
        for (Object tool : tools) {
            toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        }

        try {
            ChatRequest.Builder builder = ChatRequest.builder().messages(chatMemory.messages());
            if (!toolSpecs.isEmpty()) {
                builder.toolSpecifications(toolSpecs);
            }

            AiMessage aiMessage = chatModel.chat(builder.build()).aiMessage();
            if (aiMessage.hasToolExecutionRequests()) {
                return executeToolsAndContinue(aiMessage, chatMemory, toolSpecs);
            }

            chatMemory.add(aiMessage);
            return AgentExecutionResult.success(aiMessage.text());
        } catch (Exception e) {
            logger.error("[ToolExecutionService] Execute failed", e);
            return AgentExecutionResult.failure(e.getMessage());
        }
    }

    private AgentExecutionResult executeToolsAndContinue(
            AiMessage aiMessage, ChatMemory chatMemory, List<ToolSpecification> toolSpecs) {
        try {
            chatMemory.add(aiMessage);

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                String toolResult = executeToolCall(request);
                chatMemory.add(ToolExecutionResultMessage.from(request, toolResult));
            }

            ChatResponse continueResponse = chatModel.chat(ChatRequest.builder()
                    .messages(chatMemory.messages())
                    .toolSpecifications(toolSpecs)
                    .build());
            AiMessage continueMessage = continueResponse.aiMessage();

            if (continueMessage.hasToolExecutionRequests()) {
                return executeToolsAndContinue(continueMessage, chatMemory, toolSpecs);
            }

            chatMemory.add(continueMessage);
            return AgentExecutionResult.success(continueMessage.text());
        } catch (Exception e) {
            logger.error("[ToolExecutionService] Tool execution failed", e);
            return AgentExecutionResult.failure("Tool execution failed: " + e.getMessage());
        }
    }

    private String executeToolCall(ToolExecutionRequest request) {
        String toolName = request.name();
        try {
            Method method = toolMethodMap.get(toolName);
            Object instance = toolInstanceMap.get(toolName);
            if (method == null || instance == null) {
                return "{\"error\":\"Tool not found: " + toolName + "\"}";
            }

            Map<String, Object> arguments = objectMapper.readValue(
                    request.arguments(), new TypeReference<Map<String, Object>>() {
                    });

            Object[] args = buildArguments(method, arguments);
            method.setAccessible(true);
            Object result = method.invoke(instance, args);
            if (result == null) {
                return "null";
            }
            if (result instanceof String) {
                return (String) result;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("[ToolExecutionService] Tool call error: {}", toolName, e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private Object[] buildArguments(Method method, Map<String, Object> arguments) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            String paramName = method.getParameters()[i].getName();
            Object value = arguments.get(paramName);
            args[i] = value != null ? convertArgument(value, paramTypes[i]) : getDefaultValue(paramTypes[i]);
        }
        return args;
    }

    private Object convertArgument(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
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
        return objectMapper.convertValue(value, targetType);
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return null;
    }

    public static class AgentExecutionResult {
        private boolean success;
        private String message;
        private String error;

        public static AgentExecutionResult success(String message) {
            AgentExecutionResult result = new AgentExecutionResult();
            result.success = true;
            result.message = message;
            return result;
        }

        public static AgentExecutionResult failure(String error) {
            AgentExecutionResult result = new AgentExecutionResult();
            result.success = false;
            result.error = error;
            return result;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getError() {
            return error;
        }
    }
}
