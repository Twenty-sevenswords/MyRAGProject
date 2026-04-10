package com.yizhaoqi.smartpai.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    // 对话模型配置
    @Value("${deepseek.api.url:https://api.deepseek.com}")
    private String apiUrl;

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String model;

    @Value("${deepseek.api.temperature:0.3}")
    private Double temperature;

    @Value("${deepseek.api.max-tokens:2000}")
    private Integer maxTokens;

    @Value("${deepseek.api.timeout:60}")
    private Integer timeoutSeconds;

    // ===================== 修复：向量模型独立配置 =====================
    @Value("${embedding.api.url}")
    private String embeddingApiUrl;

    @Value("${embedding.api.key}")
    private String embeddingApiKey;

    @Value("${embedding.api.model}")
    private String embeddingModel;
    @Value("${embedding.api.dimension}")
    private Integer embeddingDimension;

    // 同步对话模型
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(apiUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    // 流式对话模型
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(apiUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    // ===================== 修复：向量模型使用正确的地址和key =====================
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingApiUrl)    // ✅ 向量地址
                .apiKey(embeddingApiKey)     // ✅ 向量key
                .modelName(embeddingModel)
                .dimensions(embeddingDimension)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}