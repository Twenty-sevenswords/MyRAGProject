package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import com.yizhaoqi.smartpai.mcp.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * Multi-agent websocket handler backed by MCP service.
 */
@Component
public class MultiAgentWebSocketHandler extends BaseWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentWebSocketHandler.class);

    private final McpService mcpService;
    private final Map<String, WebSocketSession> sessions = newSessionMap();

    public MultiAgentWebSocketHandler(McpService mcpService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.mcpService = mcpService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = ensureSessionAttribute(session, "sessionId", "sess_");
        sessions.put(sessionId, session);
        logger.info("Agent socket connected: sessionId={}, userId={}", sessionId, extractUserId(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = ensureSessionAttribute(session, "sessionId", "sess_");
        String userId = extractUserId(session);

        try {
            ChatRequest request = objectMapper.readValue(message.getPayload(), ChatRequest.class);
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                request.setUserId(userId);
            }

            mcpService.chat(request.getMessage(), sessionId, request.getUserId())
                    .subscribe(
                            event -> safeSend(session, event, logger, "agent"),
                            error -> {
                                logger.error("Agent processing failed: sessionId={}", sessionId, error);
                                safeSend(session, McpEvent.error(error.getMessage(), sessionId), logger, "agent");
                            },
                            () -> logger.info("Agent processing completed: sessionId={}", sessionId)
                    );
        } catch (Exception e) {
            logger.error("Failed to parse agent request", e);
            safeSend(session, McpEvent.error("Message parse failed: " + e.getMessage(), sessionId), logger, "agent");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = attribute(session, "sessionId", "");
        if (!sessionId.isBlank()) {
            sessions.remove(sessionId);
        }
        logger.info("Agent socket closed: sessionId={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        logger.error("Agent socket transport error: sessionId={}", attribute(session, "sessionId", ""), exception);
    }

    private String extractUserId(WebSocketSession session) {
        return attribute(session, "userId", "anonymous");
    }

    public static class ChatRequest {
        private String message;
        private String userId;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }
    }
}
