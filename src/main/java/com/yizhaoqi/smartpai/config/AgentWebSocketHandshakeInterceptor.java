package com.yizhaoqi.smartpai.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Agent WebSocket握手拦截器
 * 从URL参数中提取sessionId和userId，放入session attributes
 */
@Component
public class AgentWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, 
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            String query = servletRequest.getURI().getQuery();
            
            if (query != null) {
                String[] params = query.split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = keyValue[0];
                        String value = keyValue[1];
                        
                        if ("sessionId".equals(key)) {
                            attributes.put("sessionId", value);
                        } else if ("userId".equals(key)) {
                            attributes.put("userId", value);
                        }
                    }
                }
            }
            
            // 如果没有sessionId，生成一个
            if (!attributes.containsKey("sessionId")) {
                attributes.put("sessionId", "sess_" + System.currentTimeMillis() + "_" + 
                    Integer.toHexString((int) (Math.random() * 0xFFFFFF)));
            }
            
            // 如果没有userId，使用匿名
            if (!attributes.containsKey("userId")) {
                attributes.put("userId", "anonymous");
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, 
                                WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需处理
    }
}
