package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.handler.ChatWebSocketHandler;
import com.yizhaoqi.smartpai.handler.MultiAgentWebSocketHandler;
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
    private AgentWebSocketHandshakeInterceptor agentWebSocketHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/chat/{token}")
                .setAllowedOrigins("*"); // 允许所有来源访问，生产环境应该限制

        // 新增：多Agent协作聊天（添加拦截器提取URL参数）
        registry.addHandler(multiAgentWebSocketHandler, "/ws/agent-chat")
                .addInterceptors(agentWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
