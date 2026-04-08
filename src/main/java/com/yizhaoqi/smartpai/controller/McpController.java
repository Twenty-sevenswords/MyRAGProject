package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import com.yizhaoqi.smartpai.mcp.service.McpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 控制器
 * 提供 REST API 接口用于测试和集成
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    @Autowired
    private McpService mcpService;

    /**
     * 流式对话接口
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<McpEvent> chatStream(
            @RequestParam String message,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String userId) {
        
        if (sessionId == null) {
            sessionId = "mcp_" + System.currentTimeMillis();
        }
        if (userId == null) {
            userId = "anonymous";
        }
        
        return mcpService.chat(message, sessionId, userId);
    }

    /**
     * 同步对话接口
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId() != null ? 
                request.getSessionId() : "mcp_" + System.currentTimeMillis();
        String userId = request.getUserId() != null ? request.getUserId() : "anonymous";
        
        McpContext result = mcpService.chatSync(request.getMessage(), sessionId, userId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.getFinalReply() != null);
        response.put("reply", result.getFinalReply());
        response.put("sources", result.getSources());
        response.put("calledSkills", result.getCalledSkills());
        response.put("checkPassed", result.isCheckPassed());
        response.put("sessionId", sessionId);
        
        return response;
    }

    /**
     * 获取会话状态
     */
    @GetMapping("/session/{sessionId}")
    public McpContext getSession(@PathVariable String sessionId) {
        return mcpService.getSession(sessionId);
    }

    // 请求类
    public static class ChatRequest {
        private String message;
        private String sessionId;
        private String userId;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }
}
