package com.yizhaoqi.smartpai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LangChain4j 存储配置
 * 配置向量存储和记忆存储
 */
@Configuration
public class LangChain4jStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jStoreConfig.class);

    @Value("${langchain4j.store.type:memory}")
    private String storeType;

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Value("${elasticsearch.scheme:https}")
    private String esScheme;

    @Value("${elasticsearch.username:elastic}")
    private String esUsername;

    @Value("${elasticsearch.password:changeme}")
    private String esPassword;

    @Value("${langchain4j.store.elasticsearch.index-name:knowledge_base_embeddings}")
    private String indexName;

    /**
     * 向量存储
     * 根据配置选择内存存储或 Elasticsearch 存储
     */
    @Bean
    @Primary
    public EmbeddingStore<TextSegment> embeddingStore(ElasticsearchClient elasticsearchClient) {
        StoreType currentType = StoreType.fromString(storeType);
        
        logger.info("[LangChain4j Store] 初始化向量存储, type={}", currentType);
        
        return switch (currentType) {
            case ELASTICSEARCH -> createElasticsearchStore(elasticsearchClient);
            case MEMORY -> createMemoryStore();
        };
    }

    /**
     * 创建 Elasticsearch 向量存储
     * 使用 LangChain4j 0.36.2 API
     */
    private EmbeddingStore<TextSegment> createElasticsearchStore(ElasticsearchClient elasticsearchClient) {
        try {
            logger.info("[LangChain4j Store] 创建 Elasticsearch 向量存储: index={}", indexName);
            
            // 创建凭证提供者
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(esUsername, esPassword)
            );
            
            // 构建 RestClient
            RestClient restClient = RestClient.builder(
                            new HttpHost(esHost, esPort, esScheme))
                    .setHttpClientConfigCallback(httpClientBuilder ->
                            httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
                    .build();
            
            ElasticsearchEmbeddingStore store = ElasticsearchEmbeddingStore.builder()
                    .restClient(restClient)
                    .indexName(indexName)
                    .build();
            
            logger.info("[LangChain4j Store] Elasticsearch 向量存储创建成功");
            return store;
            
        } catch (Exception e) {
            logger.error("[LangChain4j Store] Elasticsearch 向量存储创建失败，降级使用内存存储: {}", e.getMessage());
            return createMemoryStore();
        }
    }

    /**
     * 创建内存向量存储
     */
    private EmbeddingStore<TextSegment> createMemoryStore() {
        logger.info("[LangChain4j Store] 创建内存向量存储");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 存储类型枚举
     */
    private enum StoreType {
        MEMORY,
        ELASTICSEARCH;

        public static StoreType fromString(String value) {
            if (value == null) {
                return MEMORY;
            }
            return switch (value.toLowerCase()) {
                case "elasticsearch", "es" -> ELASTICSEARCH;
                default -> MEMORY;
            };
        }
    }
}
