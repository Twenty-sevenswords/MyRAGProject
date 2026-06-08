package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends BaseWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        super(objectMapper);
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        sessions.put(userId, session);
        logger.info("WebSocket connected: userId={}, sessionId={}", userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        String payload = message.getPayload();
        try {
            if (isInternalStopCommand(payload)) {
                chatHandler.stopResponse(userId, session);
                return;
            }
            chatHandler.processMessage(userId, payload, session);
        } catch (Exception e) {
            logger.error("Message handling failed: userId={}, sessionId={}", userId, session.getId(), e);
            sendError(session, "Message handling failed: " + e.getMessage(), logger, "chat");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        sessions.remove(userId);
        logger.info("WebSocket closed: userId={}, sessionId={}, status={}", userId, session.getId(), status);
    }

    private boolean isInternalStopCommand(String payload) {
        if (payload == null || payload.isBlank() || !payload.trim().startsWith("{")) {
            return false;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(payload, Map.class);
            Object type = map.get("type");
            Object token = map.get("_internal_cmd_token");
            return "stop".equals(type) && INTERNAL_CMD_TOKEN.equals(token);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        String jwtToken = segments.length == 0 ? "" : segments[segments.length - 1];
        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        return username != null ? username : jwtToken;
    }

    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
}
