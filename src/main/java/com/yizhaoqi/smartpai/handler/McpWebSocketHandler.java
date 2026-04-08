package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.mcp.core.McpEngine;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP WebSocket 处理器
 * 企业级 AI Agent 平台的 WebSocket 接入点
 */
@Component
public class McpWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(McpWebSocketHandler.class);

    @Autowired
    private McpEngine mcpEngine;

    @Autowired
    private ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            sessionId = "mcp_" + System.currentTimeMillis() + "_" +
                    Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        }
        sessions.put(sessionId, session);
        logger.info("[MCP WebSocket] 连接建立: sessionId={}, userId={}", sessionId, extractUserId(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);
        String userId = extractUserId(session);
        String payload = message.getPayload();

        logger.info("[MCP WebSocket] 收到消息: sessionId={}, payload={}", sessionId, payload);

        try {
            // 解析请求
            ChatRequest request = objectMapper.readValue(payload, ChatRequest.class);

            if (request.getUserId() == null || request.getUserId().isEmpty()) {
                request.setUserId(userId);
            }

            // 调用 MCP 控制平面
            mcpEngine.chat(request.getMessage(), sessionId, request.getUserId())
                    .subscribe(
                        event -> sendEventSafely(session, event),
                        error -> {
                            logger.error("[MCP WebSocket] 处理错误: sessionId={}", sessionId, error);
                            sendEventSafely(session, McpEvent.error(error.getMessage(), sessionId));
                        },
                        () -> logger.info("[MCP WebSocket] 处理完成: sessionId={}", sessionId)
                    );

        } catch (Exception e) {
            logger.error("[MCP WebSocket] 解析消息失败: {}", e.getMessage(), e);
            sendEventSafely(session, McpEvent.error("消息解析失败: " + e.getMessage(), sessionId));
        }
    }

    /**
     * 安全发送事件
     */
    private void sendEventSafely(WebSocketSession session, McpEvent event) {
        if (session == null || !session.isOpen()) {
            logger.warn("[MCP WebSocket] Session 已关闭，跳过发送: event={}", event.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                    logger.debug("[MCP WebSocket] 已发送: {}", json.substring(0, Math.min(100, json.length())));
                }
            }
        } catch (Exception e) {
            logger.error("[MCP WebSocket] 发送事件失败: event={}", event.getType(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        logger.info("[MCP WebSocket] 连接关闭: sessionId={}, status={}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("[MCP WebSocket] 传输错误: sessionId={}", extractSessionId(session), exception);
    }

    private String extractSessionId(WebSocketSession session) {
        return (String) session.getAttributes().get("sessionId");
    }

    private String extractUserId(WebSocketSession session) {
        return (String) session.getAttributes().getOrDefault("userId", "anonymous");
    }

    // 请求类
    public static class ChatRequest {
        private String message;
        private String userId;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }
}
