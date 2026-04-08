package com.yizhaoqi.smartpai.mcp.event;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 事件（用于流式返回）
 */
@Data
public class McpEvent {

    /** 事件类型 */
    private String type;

    /** Agent/Skill 名称 */
    private String agent;

    /** 消息 */
    private String message;

    /** 数据 */
    private Object data;

    /** 会话ID */
    private String sessionId;

    /** 时间戳 */
    private long timestamp = System.currentTimeMillis();

    // ========== 事件类型常量 ==========

    public static final String TYPE_START = "start";
    public static final String TYPE_COMPLETE = "complete";
    public static final String TYPE_CHUNK = "stream";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_STATE = "state";

    // ========== 工厂方法 ==========

    public static McpEvent state(String agent, String message, String sessionId) {
        McpEvent event = new McpEvent();
        event.setType(TYPE_START);
        event.setAgent(agent);
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }

    public static McpEvent complete(String agent, String message, String sessionId) {
        McpEvent event = new McpEvent();
        event.setType(TYPE_COMPLETE);
        event.setAgent(agent);
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }

    public static McpEvent chunk(String agent, String chunk, String sessionId) {
        McpEvent event = new McpEvent();
        event.setType(TYPE_CHUNK);
        event.setAgent(agent);
        event.setMessage(chunk);
        event.setSessionId(sessionId);
        return event;
    }

    public static McpEvent finalReply(String reply, Object sources, String sessionId) {
        McpEvent event = new McpEvent();
        event.setType(TYPE_FINAL);
        event.setAgent("system");
        event.setMessage(reply);
        event.setData(sources);
        event.setSessionId(sessionId);
        return event;
    }

    public static McpEvent error(String message, String sessionId) {
        McpEvent event = new McpEvent();
        event.setType(TYPE_ERROR);
        event.setAgent("system");
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }
}
