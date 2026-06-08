package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared utilities for websocket handlers.
 */
public abstract class BaseWebSocketHandler extends TextWebSocketHandler {

    protected final ObjectMapper objectMapper;

    protected BaseWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected String attribute(WebSocketSession session, String key, String defaultValue) {
        Object value = session.getAttributes().get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text.isBlank() ? defaultValue : text;
    }

    protected String ensureSessionAttribute(WebSocketSession session, String key, String prefix) {
        String existing = attribute(session, key, "");
        if (!existing.isBlank()) {
            return existing;
        }
        String generated = prefix + System.currentTimeMillis() + "_" +
                Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        session.getAttributes().put(key, generated);
        return generated;
    }

    protected Map<String, WebSocketSession> newSessionMap() {
        return new ConcurrentHashMap<>();
    }

    protected void safeSend(WebSocketSession session, Object payload, Logger logger, String label) {
        if (session == null || !session.isOpen()) {
            logger.warn("[{}] session closed, skip sending", label);
            return;
        }
        try {
            String json = payload instanceof String
                    ? (String) payload
                    : objectMapper.writeValueAsString(payload);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            logger.error("[{}] send failed", label, e);
        }
    }

    protected void sendError(WebSocketSession session, String message, Logger logger, String label) {
        safeSend(session, Map.of("error", message), logger, label);
    }
}
