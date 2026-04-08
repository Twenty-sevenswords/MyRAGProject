package com.yizhaoqi.smartpai.mcp.service;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.core.McpEngine;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * MCP 服务层
 * 提供统一的 AI Agent 服务接口
 */
@Service
public class McpService {

    private static final Logger logger = LoggerFactory.getLogger(McpService.class);

    @Autowired
    private McpEngine mcpEngine;

    /**
     * 执行对话（流式）
     */
    public Flux<McpEvent> chat(String message, String sessionId, String userId) {
        logger.info("[McpService] 执行对话: sessionId={}, userId={}", sessionId, userId);
        return mcpEngine.chat(message, sessionId, userId);
    }

    /**
     * 执行对话（同步，阻塞）
     */
    public McpContext chatSync(String message, String sessionId, String userId) {
        logger.info("[McpService] 同步执行对话: sessionId={}, userId={}", sessionId, userId);
        
        // 收集所有事件
        McpContext result = new McpContext(sessionId, userId, message);
        
        mcpEngine.chat(message, sessionId, userId)
                .doOnNext(event -> {
                    if ("final".equals(event.getType())) {
                        result.setFinalReply(event.getMessage());
                    }
                })
                .blockLast();
        
        return result;
    }

    /**
     * 获取会话状态
     */
    public McpContext getSession(String sessionId) {
        return mcpEngine.getSession(sessionId);
    }
}
