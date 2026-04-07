package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                          @Value("${deepseek.api.key}") String apiKey,
                          @Value("${deepseek.api.model}") String model,
                          AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }

    // ========== 新增：同步 chat 方法（用于 Agent） ==========

    /**
     * 同步调用，等待完整响应（用于 IntentAgent/CheckAgent 等需要确定输出的场景）
     */
    public String chat(String prompt) {
        Map<String, Object> request = buildSimpleRequest(prompt);

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // 同步等待

            return extractContent(response);

        } catch (Exception e) {
            logger.error("DeepSeek同步调用失败: {}", e.getMessage());
            throw new RuntimeException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 带上下文的同步调用
     */
    public String chat(String systemPrompt, String userMessage) {
        Map<String, Object> request = buildRequestWithSystem(systemPrompt, userMessage);

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return extractContent(response);

        } catch (Exception e) {
            logger.error("DeepSeek同步调用失败: {}", e.getMessage());
            throw new RuntimeException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    // ========== 原有：流式方法（用于聊天） ==========

    public void streamResponse(String userMessage,
                               String context,
                               List<Map<String, String>> history,
                               Consumer<String> onChunk,
                               Consumer<Throwable> onError) {

        Map<String, Object> request = buildRequest(userMessage, context, history);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, onChunk),
                        onError
                );
    }

    // ========== 私有辅助方法 ==========

    private Map<String, Object> buildSimpleRequest(String prompt) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        request.put("stream", false); // 非流式

        // 生成参数（Agent需要确定性输出，温度调低）
        request.put("temperature", 0.3);
        request.put("max_tokens", 2000);

        return request;
    }

    private Map<String, Object> buildRequestWithSystem(String systemPrompt, String userMessage) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        request.put("stream", false);
        request.put("temperature", 0.3);
        request.put("max_tokens", 2000);

        return request;
    }

    private String extractContent(String jsonResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(jsonResponse);

            return node.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("");
        } catch (Exception e) {
            logger.error("解析响应失败: {}", e.getMessage());
            throw new RuntimeException("解析LLM响应失败", e);
        }
    }

    private Map<String, Object> buildRequest(String userMessage,
                                             String context,
                                             List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
                                                    String context,
                                                    List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
                "role", "system",
                "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
                "role", "user",
                "content", userMessage
        ));

        return messages;
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content")
                    .asText("");

            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
}