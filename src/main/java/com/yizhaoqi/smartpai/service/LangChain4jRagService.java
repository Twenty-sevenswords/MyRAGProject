package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.dto.DocumentContent;
import com.yizhaoqi.smartpai.dto.RagAnswer;
import com.yizhaoqi.smartpai.util.PromptLoader;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Central RAG service.
 * - Keeps legacy embedding-store APIs.
 * - Provides permission-aware generation for LangGraph nodes.
 */
@Service
public class LangChain4jRagService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jRagService.class);

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a reliable assistant.
            Answer only based on the provided context.
            If context is insufficient, explicitly say so.
            """;

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final HybridSearchService hybridSearchService;
    private final PromptLoader promptLoader;

    public LangChain4jRagService(ChatLanguageModel chatModel,
                                 StreamingChatLanguageModel streamingChatModel,
                                 EmbeddingModel embeddingModel,
                                 EmbeddingStore<TextSegment> embeddingStore,
                                 HybridSearchService hybridSearchService,
                                 PromptLoader promptLoader) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.hybridSearchService = hybridSearchService;
        this.promptLoader = promptLoader;
    }

    // ---------- Legacy indexing/search on embedding store ----------

    public void indexDocument(String content, String documentId, String fileName, String userId, List<String> tags) {
        Metadata metadata = new Metadata();
        metadata.put("documentId", documentId);
        metadata.put("fileName", fileName);
        metadata.put("userId", userId);
        metadata.put("tags", tags != null ? String.join(",", tags) : "");

        Document document = Document.from(content, metadata);
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
        List<TextSegment> segments = splitter.split(document);

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
    }

    public void indexDocuments(List<DocumentContent> documents) {
        List<TextSegment> allSegments = new ArrayList<>();
        for (DocumentContent doc : documents) {
            Metadata metadata = new Metadata();
            metadata.put("documentId", doc.getDocumentId());
            metadata.put("fileName", doc.getFileName());
            metadata.put("userId", doc.getUserId());
            metadata.put("tags", doc.getTags() != null ? String.join(",", doc.getTags()) : "");

            Document document = Document.from(doc.getContent(), metadata);
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
            allSegments.addAll(splitter.split(document));
        }

        List<Embedding> embeddings = embeddingModel.embedAll(allSegments).content();
        embeddingStore.addAll(embeddings, allSegments);
    }

    public List<EmbeddingSearchResultDto> search(String query, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.5)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        return searchResult.matches().stream()
                .map(this::toEmbeddingSearchResult)
                .collect(Collectors.toList());
    }

    public List<EmbeddingSearchResultDto> searchWithPermission(String query, String userId, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 2)
                .minScore(0.5)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        return searchResult.matches().stream()
                .filter(match -> hasPermission(match, userId))
                .limit(maxResults)
                .map(this::toEmbeddingSearchResult)
                .collect(Collectors.toList());
    }

    public String ask(String question, int maxResults) {
        List<EmbeddingSearchResultDto> searchResults = search(question, maxResults);
        if (searchResults.isEmpty()) {
            return "No relevant information found in the knowledge base.";
        }
        String context = buildEmbeddingContext(searchResults);
        return chatModel.chat(buildPrompt(context, question));
    }

    public String ask(String systemPrompt, String question, int maxResults) {
        List<EmbeddingSearchResultDto> searchResults = search(question, maxResults);
        if (searchResults.isEmpty()) {
            return "No relevant information found in the knowledge base.";
        }

        String context = buildEmbeddingContext(searchResults);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt != null ? systemPrompt : getSystemPrompt()));
        messages.add(UserMessage.from(buildPrompt(context, question)));
        ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
        return response.aiMessage().text();
    }

    public void askStream(String question, int maxResults, Consumer<String> onChunk, Consumer<Throwable> onError) {
        List<EmbeddingSearchResultDto> searchResults = search(question, maxResults);
        if (searchResults.isEmpty()) {
            onChunk.accept("No relevant information found in the knowledge base.");
            return;
        }

        String context = buildEmbeddingContext(searchResults);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(getSystemPrompt()));
        messages.add(UserMessage.from(buildPrompt(context, question)));

        ChatRequest request = ChatRequest.builder().messages(messages).build();
        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onChunk.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                logger.debug("[LangChain4jRagService] stream completed");
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
    }

    public Flux<String> askStreamFlux(String question, int maxResults) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<EmbeddingSearchResultDto> searchResults = search(question, maxResults);
        if (searchResults.isEmpty()) {
            sink.tryEmitNext("No relevant information found in the knowledge base.");
            sink.tryEmitComplete();
            return sink.asFlux();
        }

        String context = buildEmbeddingContext(searchResults);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(getSystemPrompt()));
        messages.add(UserMessage.from(buildPrompt(context, question)));

        ChatRequest request = ChatRequest.builder().messages(messages).build();
        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
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

    // ---------- LangGraph-facing APIs (permission-aware) ----------

    public RagAnswer askWithPermission(String question, String userId, int topK) {
        List<com.yizhaoqi.smartpai.dto.SearchResult> results =
                hybridSearchService.searchWithPermission(question, userId, topK);

        if (results == null || results.isEmpty()) {
            return new RagAnswer("No relevant information found in the knowledge base.", new ArrayList<>(), false);
        }

        String prompt = buildPrompt(buildHybridContext(results), question);
        String reply = chatModel.chat(prompt);
        return new RagAnswer(reply, results, true);
    }

    public String ask(String question, String userId) {
        return askWithPermission(question, userId, 8).getReply();
    }

    // ---------- Helpers ----------

    private boolean hasPermission(EmbeddingMatch<TextSegment> match, String userId) {
        Metadata metadata = match.embedded().metadata();
        String docUserId = metadata.getString("userId");
        if (docUserId == null || docUserId.isEmpty()) {
            return true;
        }
        if (userId != null && userId.equals(docUserId)) {
            return true;
        }
        String tags = metadata.getString("tags");
        return tags != null && !tags.isEmpty() && userId != null;
    }

    private EmbeddingSearchResultDto toEmbeddingSearchResult(EmbeddingMatch<TextSegment> match) {
        EmbeddingSearchResultDto result = new EmbeddingSearchResultDto();
        result.setContent(match.embedded().text());
        result.setScore(match.score());

        Metadata metadata = match.embedded().metadata();
        result.setDocumentId(metadata.getString("documentId"));
        result.setFileName(metadata.getString("fileName"));
        return result;
    }

    private String buildEmbeddingContext(List<EmbeddingSearchResultDto> results) {
        StringBuilder context = new StringBuilder();
        context.append("Knowledge snippets:\n");

        for (int i = 0; i < results.size(); i++) {
            EmbeddingSearchResultDto r = results.get(i);
            context.append("[").append(i + 1).append("] ")
                    .append(r.getContent())
                    .append("\nSource: ")
                    .append(r.getFileName())
                    .append("\n\n");
        }
        return context.toString();
    }

    private String buildHybridContext(List<com.yizhaoqi.smartpai.dto.SearchResult> results) {
        StringBuilder context = new StringBuilder();
        context.append("Knowledge snippets:\n");

        for (int i = 0; i < Math.min(5, results.size()); i++) {
            com.yizhaoqi.smartpai.dto.SearchResult r = results.get(i);
            String fileName = r.getFileName() != null ? r.getFileName() : r.getFileMd5();
            context.append("[").append(i + 1).append("] ")
                    .append(r.getTextContent() == null ? "" : r.getTextContent())
                    .append("\nSource: ").append(fileName)
                    .append("\n\n");
        }
        return context.toString();
    }

    private String buildPrompt(String context, String question) {
        return context + "\nQuestion: " + question;
    }

    private String getSystemPrompt() {
        return promptLoader.loadWithFallback("prompts/system-rag.txt", DEFAULT_SYSTEM_PROMPT);
    }

    // ---------- Legacy DTO (renamed from SearchResult) ----------

    public static class EmbeddingSearchResultDto {
        private String content;
        private double score;
        private String documentId;
        private String fileName;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getDocumentId() {
            return documentId;
        }

        public void setDocumentId(String documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
    }
}
