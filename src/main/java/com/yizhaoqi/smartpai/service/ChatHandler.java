package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.ChatMessage;
import com.yizhaoqi.smartpai.dto.QaMemoryEntry;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.service.chat.StreamCompletionDetector;
import com.yizhaoqi.smartpai.util.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handles websocket chat message workflow.
 */
@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private static final Charset GBK = Charset.forName("GBK");
    private static final String MOJIBAKE_TOKENS = "";

    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final LangChain4jChatService chatService;
    private final ChatContextService contextService;
    private final QaMemoryService qaMemoryService;
    private final StreamCompletionDetector completionDetector;
    private final ObjectMapper objectMapper;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();
    private final Map<String, String> sessionMapping = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       HybridSearchService searchService,
                       LangChain4jChatService chatService,
                       ChatContextService contextService,
                       QaMemoryService qaMemoryService,
                       StreamCompletionDetector completionDetector) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.chatService = chatService;
        this.contextService = contextService;
        this.qaMemoryService = qaMemoryService;
        this.completionDetector = completionDetector;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        String wsSessionId = session.getId();
        logger.info("Start processing message, userId={}, wsSessionId={}", userId, wsSessionId);

        try {
            String businessSessionId = getOrCreateSessionId(userId, wsSessionId);
            responseBuilders.put(wsSessionId, new StringBuilder());

            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(wsSessionId, responseFuture);

            Optional<QaMemoryEntry> cachedAnswer = qaMemoryService.findSimilarAnswer(userMessage, userId, null);
            if (cachedAnswer.isPresent()) {
                QaMemoryEntry memoryEntry = cachedAnswer.get();
                String cachedReply = normalizePotentialMojibake(memoryEntry.getAnswer());
                if (isLikelyGarbled(cachedReply)) {
                    qaMemoryService.deleteMemoryById(userId, memoryEntry.getId());
                    logger.warn("Discarded garbled QA cache entry. userId={}, memoryId={}", userId, memoryEntry.getId());
                } else {
                    if (!cachedReply.equals(memoryEntry.getAnswer())) {
                        qaMemoryService.deleteMemoryById(userId, memoryEntry.getId());
                        if (shouldCacheAnswer(cachedReply)) {
                            qaMemoryService.saveSimpleMemory(userMessage, userId, cachedReply, List.of(), true);
                        }
                        logger.info("Auto-repaired cached reply text. userId={}, memoryId={}", userId, memoryEntry.getId());
                    }
                    sendResponseChunk(session, cachedReply);
                    sendCompletionNotification(session);
                    contextService.addRound(businessSessionId, userMessage, cachedReply);
                    cleanupSessionState(wsSessionId);
                    logger.info("Cache hit, directly responded from QA memory. userId={}, session={}", userId, businessSessionId);
                    return;
                }
            }

            com.yizhaoqi.smartpai.dto.ChatContext context = contextService.getOrCreateContext(businessSessionId, userId);
            List<ChatMessage> chatHistory = context.getRecentHistory(10);
            List<Map<String, String>> history = PromptBuilder.buildHistoryMessages(chatHistory);

            List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);
            String ragContext = buildContext(searchResults);

            final String finalBusinessSessionId = businessSessionId;
            final String finalUserMessage = userMessage;
            final String finalUserId = userId;
            final List<SearchResult> finalSearchResults = searchResults;

            chatService.streamChat(
                    ragContext,
                    userMessage,
                    history,
                    chunk -> {
                        String safeChunk = normalizePotentialMojibake(chunk);
                        StringBuilder responseBuilder = responseBuilders.get(wsSessionId);
                        if (responseBuilder != null) {
                            responseBuilder.append(safeChunk);
                        }
                        sendResponseChunk(session, safeChunk);
                    },
                    error -> {
                        handleError(session, error);
                        sendCompletionNotification(session);
                        responseFuture.completeExceptionally(error);
                        cleanupSessionState(wsSessionId);
                    }
            );

            completionDetector.awaitCompletion(wsSessionId, responseBuilders.get(wsSessionId))
                    .thenAccept(completeResponse -> {
                        if (responseFuture.isCompletedExceptionally()) {
                            return;
                        }
                        handleCompletedResponse(
                                session,
                                wsSessionId,
                                finalBusinessSessionId,
                                finalUserMessage,
                                finalUserId,
                                finalSearchResults,
                                completeResponse,
                                responseFuture
                        );
                    })
                    .exceptionally(error -> {
                        logger.error("Completion detection failed, wsSessionId={}", wsSessionId, error);
                        if (!responseFuture.isDone()) {
                            responseFuture.completeExceptionally(error);
                        }
                        cleanupSessionState(wsSessionId);
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Process message failed, wsSessionId={}", wsSessionId, e);
            handleError(session, e);
            CompletableFuture<String> future = responseFutures.get(wsSessionId);
            cleanupSessionState(wsSessionId);
            if (future != null && !future.isDone()) {
                future.completeExceptionally(e);
            }
        }
    }

    private void handleCompletedResponse(WebSocketSession session,
                                         String wsSessionId,
                                         String businessSessionId,
                                         String userMessage,
                                         String userId,
                                         List<SearchResult> searchResults,
                                         String completeResponse,
                                         CompletableFuture<String> responseFuture) {
        String safeResponse = completeResponse == null ? "" : normalizePotentialMojibake(completeResponse);
        try {
            if (!responseFuture.isDone()) {
                responseFuture.complete(safeResponse);
            }

            sendCompletionNotification(session);
            contextService.addRound(businessSessionId, userMessage, safeResponse);
            logger.info("Dialogue round persisted, session={}", businessSessionId);

            try {
                if (shouldCacheAnswer(safeResponse)) {
                    qaMemoryService.saveSimpleMemory(userMessage, userId, safeResponse, searchResults, true);
                } else {
                    logger.warn("Skip caching garbled-looking response. userId={}, session={}", userId, businessSessionId);
                }
            } catch (Exception memoryError) {
                logger.warn("Save QA memory failed: {}", memoryError.getMessage());
            }
        } finally {
            cleanupSessionState(wsSessionId);
            logger.info("Message flow completed, userId={}, wsSessionId={}", userId, wsSessionId);
        }
    }

    private void cleanupSessionState(String wsSessionId) {
        responseBuilders.remove(wsSessionId);
        responseFutures.remove(wsSessionId);
    }

    private String getOrCreateSessionId(String userId, String wsSessionId) {
        String sessionId = "session:" + userId;
        sessionMapping.put(wsSessionId, sessionId);
        return sessionId;
    }

    public String getSessionId(String wsSessionId) {
        return sessionMapping.get(wsSessionId);
    }

    public void clearSession(String wsSessionId) {
        sessionMapping.remove(wsSessionId);
        cleanupSessionState(wsSessionId);
        stopFlags.remove(wsSessionId);
    }

    private String buildContext(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return "";
        }

        final int maxSnippetLength = 300;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            if (snippet != null && snippet.length() > maxSnippetLength) {
                snippet = snippet.substring(0, maxSnippetLength) + "...";
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            context.append(String.format("[%d] (%s) %s%n", i + 1, fileLabel, snippet));
        }
        return context.toString();
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        try {
            if (Boolean.TRUE.equals(stopFlags.get(session.getId()))) {
                logger.debug("Stop flag is true, skip chunk sending. wsSessionId={}", session.getId());
                return;
            }
            Map<String, String> payload = Map.of("chunk", chunk);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            logger.error("Send response chunk failed, wsSessionId={}", session.getId(), e);
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            long currentTime = System.currentTimeMillis();
            Map<String, Object> payload = Map.of(
                    "type", "completion",
                    "status", "finished",
                    "message", "Response completed.",
                    "timestamp", currentTime,
                    "date", java.time.LocalDateTime.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            logger.error("Send completion notification failed, wsSessionId={}", session.getId(), e);
        }
    }

    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("AI service error, wsSessionId={}", session.getId(), error);
        try {
            Map<String, String> payload = Map.of("error", "AI service is temporarily unavailable. Please try again later.");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            logger.error("Send error message failed, wsSessionId={}", session.getId(), e);
        }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        String wsSessionId = session.getId();
        logger.info("Received stop request, userId={}, wsSessionId={}", userId, wsSessionId);

        stopFlags.put(wsSessionId, true);
        try {
            long currentTime = System.currentTimeMillis();
            Map<String, Object> payload = Map.of(
                    "type", "stop",
                    "message", "Response stopped.",
                    "timestamp", currentTime,
                    "date", java.time.Instant.ofEpochMilli(currentTime).toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            logger.error("Send stop acknowledgement failed, wsSessionId={}", wsSessionId, e);
        }

        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS)
                .execute(() -> stopFlags.remove(wsSessionId));
    }

    private boolean shouldCacheAnswer(String response) {
        return response != null && !response.isBlank() && !isLikelyGarbled(response);
    }

    private String normalizePotentialMojibake(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }

        String repaired = tryReencode(text, GBK, StandardCharsets.UTF_8);
        if (isBetterCandidate(text, repaired)) {
            return repaired;
        }

        String latin1Repaired = tryReencode(text, StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);
        if (isBetterCandidate(text, latin1Repaired)) {
            return latin1Repaired;
        }

        return text;
    }

    private String tryReencode(String text, Charset sourceCharset, Charset targetCharset) {
        try {
            return new String(text.getBytes(sourceCharset), targetCharset);
        } catch (Exception ex) {
            return text;
        }
    }

    private boolean isBetterCandidate(String original, String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.equals(original)) {
            return false;
        }
        int originalScore = getGarbledScore(original);
        int candidateScore = getGarbledScore(candidate);
        return candidateScore + 2 <= originalScore;
    }

    private boolean isLikelyGarbled(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return getGarbledScore(text) >= 4;
    }

    private int getGarbledScore(String text) {
        int score = 0;
        boolean hasHan = false;
        boolean hasKana = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFD') {
                score += 4;
            }
            if (MOJIBAKE_TOKENS.indexOf(ch) >= 0) {
                score += 1;
            }
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                hasHan = true;
            }
            if (block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA) {
                hasKana = true;
            }
            if (Character.getType(ch) == Character.PRIVATE_USE) {
                score += 2;
            }
        }

        if (text.contains("浣犲ソ") || text.contains("锟")) {
            score += 4;
        }
        if (hasHan && hasKana) {
            score += 3;
        }
        return score;
    }
}


