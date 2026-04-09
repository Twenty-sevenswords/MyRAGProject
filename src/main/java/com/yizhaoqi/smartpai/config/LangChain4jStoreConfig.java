package com.yizhaoqi.smartpai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * LangChain4j 存储配置
 * 配置向量存储和记忆存储
 */
@Configuration
public class LangChain4jStoreConfig {

    @Value("${langchain4j.store.type:memory}")
    private String storeType;

    /**
     * 向量存储（内存版本，用于开发测试）
     * 生产环境可替换为 Elasticsearch 或其他向量数据库
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 使用内存存储（开发环境）
        // 生产环境应使用 ElasticsearchEmbeddingStore 或其他持久化存储
        return new InMemoryEmbeddingStore<>();
    }

    // TODO: 生产环境使用 Elasticsearch 存储
    // @Bean
    // public EmbeddingStore<TextSegment> elasticsearchEmbeddingStore() {
    //     return ElasticsearchEmbeddingStore.builder()
    //             .serverUrl("http://localhost:9200")
    //             .indexName("knowledge_base")
    //             .build();
    // }
}
