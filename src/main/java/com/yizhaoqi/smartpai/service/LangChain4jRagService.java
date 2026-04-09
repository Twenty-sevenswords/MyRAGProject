package com.yizhaoqi.smartpai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LangChain4j RAG 服务
 * 提供完整的 RAG（检索增强生成）能力
 */
@Service
public class LangChain4jRagService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jRagService.class);

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // 默认系统提示
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个智能问答助手。请根据以下知识库内容回答用户问题。
            
            规则：
            1. 只使用提供的知识库内容回答
            2. 如果知识库中没有相关信息，请明确告知
            3. 回答要准确、简洁、专业
            4. 引用来源时标注文档名称
            """;

    public LangChain4jRagService(ChatLanguageModel chatModel,
                                  StreamingChatLanguageModel streamingChatModel,
                                  EmbeddingModel embeddingModel,
                                  EmbeddingStore<TextSegment> embeddingStore) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    // ========== 文档索引 ==========

    /**
     * 索引单个文档
     */
    public void indexDocument(String content, String documentId, String fileName, 
                              String userId, List<String> tags) {
        logger.info("[LangChain4j RAG] 索引文档: id={}, file={}, length={}", 
                documentId, fileName, content.length());
        
        // 创建元数据
        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("documentId", documentId);
        metadataMap.put("fileName", fileName);
        metadataMap.put("userId", userId);
        metadataMap.put("tags", tags != null ? String.join(",", tags) : "");
        Metadata metadata = Metadata.from(metadataMap);
        
        // 创建文档
        Document document = Document.from(content, metadata);
        
        // 分割文档
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.split(document);
        
        // 批量嵌入并存储
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        
        logger.info("[LangChain4j RAG] 文档索引完成: segments={}", segments.size());
    }

    /**
     * 批量索引文档
     */
    public void indexDocuments(List<DocumentContent> documents) {
        logger.info("[LangChain4j RAG] 批量索引文档: count={}", documents.size());
        
        List<TextSegment> allSegments = new ArrayList<>();
        
        for (DocumentContent doc : documents) {
            Map<String, Object> metadataMap = new HashMap<>();
            metadataMap.put("documentId", doc.getDocumentId());
            metadataMap.put("fileName", doc.getFileName());
            metadataMap.put("userId", doc.getUserId());
            metadataMap.put("tags", doc.getTags() != null ? String.join(",", doc.getTags()) : "");
            Metadata metadata = Metadata.from(metadataMap);
            
            Document document = Document.from(doc.getContent(), metadata);
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
            allSegments.addAll(splitter.split(document));
        }
        
        // 批量嵌入并存储
        List<Embedding> embeddings = embeddingModel.embedAll(allSegments).content();
        embeddingStore.addAll(embeddings, allSegments);
        
        logger.info("[LangChain4j RAG] 批量索引完成: totalSegments={}", allSegments.size());
    }

    // ========== 检索 ==========

    /**
     * 相似度检索
     */
    public List<SearchResult> search(String query, int maxResults) {
        logger.debug("[LangChain4j RAG] 检索: query={}, maxResults={}", query, maxResults);
        
        // 嵌入查询
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        
        // 搜索
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.5)
                .build();
        
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        
        // 转换结果
        return searchResult.matches().stream()
                .map(this::toSearchResult)
                .collect(Collectors.toList());
    }

    /**
     * 带权限过滤的检索
     */
    public List<SearchResult> searchWithPermission(String query, String userId,
                                                    int maxResults) {
        logger.debug("[LangChain4j RAG] 带权限检索: query={}, userId={}", query, userId);
        
        // 嵌入查询
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        
        // 搜索（不带过滤，在结果中手动过滤）
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 2)  // 多取一些，后续过滤
                .minScore(0.5)
                .build();
        
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        
        // 手动过滤权限
        return searchResult.matches().stream()
                .filter(match -> {
                    // 检查用户权限
                    Metadata metadata = match.embedded().metadata();
                    String docUserId = metadata.getString("userId");
                    String tags = metadata.getString("tags");
                    // TODO: 实现实际的权限过滤逻辑
                    return true;
                })
                .limit(maxResults)
                .map(this::toSearchResult)
                .collect(Collectors.toList());
    }

    private SearchResult toSearchResult(EmbeddingMatch<TextSegment> match) {
        SearchResult result = new SearchResult();
        result.setContent(match.embedded().text());
        result.setScore(match.score());
        
        Metadata metadata = match.embedded().metadata();
        result.setDocumentId(metadata.getString("documentId"));
        result.setFileName(metadata.getString("fileName"));
        
        return result;
    }

    // ========== RAG 生成 ==========

    /**
     * RAG 问答（同步）
     */
    public String ask(String question, int maxResults) {
        // 1. 检索相关内容
        List<SearchResult> searchResults = search(question, maxResults);
        
        if (searchResults.isEmpty()) {
            return "抱歉，我在知识库中没有找到相关信息。";
        }
        
        // 2. 构建上下文
        String context = buildContext(searchResults);
        
        // 3. 生成回答
        String prompt = buildPrompt(context, question);
        ChatResponse response = chatModel.chat(prompt);
        return response.aiMessage().text();
    }

    /**
     * RAG 问答（带系统提示，同步）
     */
    public String ask(String systemPrompt, String question, int maxResults) {
        // 1. 检索相关内容
        List<SearchResult> searchResults = search(question, maxResults);
        
        if (searchResults.isEmpty()) {
            return "抱歉，我在知识库中没有找到相关信息。";
        }
        
        // 2. 构建上下文
        String context = buildContext(searchResults);
        
        // 3. 生成回答
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt != null ? systemPrompt : DEFAULT_SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildPrompt(context, question)));
        
        ChatResponse response = chatModel.chat(messages);
        return response.aiMessage().text();
    }

    /**
     * RAG 问答（流式）
     */
    public void askStream(String question, int maxResults, 
                          Consumer<String> onChunk, Consumer<Throwable> onError) {
        // 1. 检索相关内容
        List<SearchResult> searchResults = search(question, maxResults);
        
        if (searchResults.isEmpty()) {
            onChunk.accept("抱歉，我在知识库中没有找到相关信息。");
            return;
        }
        
        // 2. 构建上下文
        String context = buildContext(searchResults);
        
        // 3. 流式生成回答
        String prompt = buildPrompt(context, question);
        
        streamingChatModel.chat(prompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onChunk.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                logger.debug("[LangChain4j RAG] 流式生成完成");
            }

            @Override
            public void onError(Throwable error) {
                logger.error("[LangChain4j RAG] 流式生成错误", error);
                onError.accept(error);
            }
        });
    }

    /**
     * RAG 问答（流式，返回 Flux）
     */
    public Flux<String> askStreamFlux(String question, int maxResults) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        
        // 1. 检索相关内容
        List<SearchResult> searchResults = search(question, maxResults);
        
        if (searchResults.isEmpty()) {
            sink.tryEmitNext("抱歉，我在知识库中没有找到相关信息。");
            sink.tryEmitComplete();
            return sink.asFlux();
        }
        
        // 2. 构建上下文
        String context = buildContext(searchResults);
        
        // 3. 流式生成回答
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(DEFAULT_SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildPrompt(context, question)));
        
        streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                sink.tryEmitError(error);
            }
        });
        
        return sink.asFlux();
    }

    // ========== 辅助方法 ==========

    private String buildContext(List<SearchResult> results) {
        StringBuilder context = new StringBuilder();
        context.append("【知识库内容】\n");
        
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            context.append(String.format("[%d] %s\n来源：%s\n\n", 
                    i + 1, r.getContent(), r.getFileName()));
        }
        
        return context.toString();
    }

    private String buildPrompt(String context, String question) {
        return String.format("%s\n\n用户问题：%s", context, question);
    }

    // ========== 内部类 ==========

    /**
     * 文档内容
     */
    public static class DocumentContent {
        private String documentId;
        private String fileName;
        private String content;
        private String userId;
        private List<String> tags;

        // Getters and Setters
        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        private String content;
        private double score;
        private String documentId;
        private String fileName;

        // Getters and Setters
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
    }
}
