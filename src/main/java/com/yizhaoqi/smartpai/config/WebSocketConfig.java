package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.handler.ChatWebSocketHandler;
import com.yizhaoqi.smartpai.handler.MultiAgentWebSocketHandler;
import com.yizhaoqi.smartpai.handler.McpWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;
    @Autowired
    private MultiAgentWebSocketHandler multiAgentWebSocketHandler;
    @Autowired
    private McpWebSocketHandler mcpWebSocketHandler;
    @Autowired
    private AgentWebSocketHandshakeInterceptor agentWebSocketHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 普通聊天
        registry.addHandler(chatWebSocketHandler, "/chat/{token}")
                .setAllowedOrigins("*");

        // 多 Agent 协作聊天（LangGraph）
        registry.addHandler(multiAgentWebSocketHandler, "/ws/agent-chat")
                .addInterceptors(agentWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");

        // MCP 企业级 AI Agent 平台
        registry.addHandler(mcpWebSocketHandler, "/ws/mcp")
                .addInterceptors(agentWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
