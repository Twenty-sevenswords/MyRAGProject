package com.yizhaoqi.smartpai.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.MultiAgentChatService;
import com.yizhaoqi.smartpai.service.agent.AgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class MultiAgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentWebSocketHandler.class);

    @Autowired
    private MultiAgentChatService multiAgentChatService;

    @Autowired
    private ObjectMapper objectMapper;

    // 会话管理
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // 用于异步处理
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId == null) {
            sessionId = "sess_" + System.currentTimeMillis() + "_" + 
                Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        }
        sessions.put(sessionId, session);
        logger.info("Agent WebSocket连接建立: sessionId={}, userId={}", sessionId, extractUserId(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);
        String userId = extractUserId(session);
        String payload = message.getPayload();

        logger.info("收到Agent消息: sessionId={}, payload={}", sessionId, payload);

        // 解析用户消息
        ChatRequest request = objectMapper.readValue(payload, ChatRequest.class);
        
        // 如果消息中没有userId，使用session中的
        if (request.getUserId() == null || request.getUserId().isEmpty()) {
            request.setUserId(userId);
        }

        // 调用多Agent服务
        multiAgentChatService.chat(request.getMessage(), sessionId, request.getUserId())
                .doOnNext(event -> logger.debug("发送事件: type={}, agent={}", event.getType(), event.getAgent()))
                .subscribe(
                    event -> sendEventSafely(session, event),
                    error -> {
                        logger.error("Agent处理错误: sessionId={}", sessionId, error);
                        sendEventSafely(session, AgentOrchestrator.AgentEvent.error(error.getMessage(), sessionId));
                    },
                    () -> logger.info("Agent处理完成: sessionId={}", sessionId)
                );
    }

    /**
     * 安全发送事件到WebSocket
     */
    private void sendEventSafely(WebSocketSession session, AgentOrchestrator.AgentEvent event) {
        if (session == null || !session.isOpen()) {
            logger.warn("Session已关闭，跳过发送: event={}", event.getType());
            return;
        }
        
        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                    logger.debug("已发送事件: {}", json.substring(0, Math.min(100, json.length())));
                }
            }
        } catch (Exception e) {
            logger.error("发送事件失败: event={}", event.getType(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        logger.info("Agent WebSocket连接关闭: sessionId={}, status={}", sessionId, status);
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("WebSocket传输错误: sessionId={}", extractSessionId(session), exception);
    }

    private String extractSessionId(WebSocketSession session) {
        // 从session attributes获取（由拦截器设置）
        return (String) session.getAttributes().get("sessionId");
    }
    
    private String extractUserId(WebSocketSession session) {
        // 从session attributes获取（由拦截器设置）
        return (String) session.getAttributes().getOrDefault("userId", "anonymous");
    }

    // 内部请求类
    public static class ChatRequest {
        private String message;
        private String userId;
        // getter/setter
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }
}