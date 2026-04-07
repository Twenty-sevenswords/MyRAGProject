package com.yizhaoqi.smartpai.langgraph.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 图执行事件 - 用于流式推送执行状态
 * 与前端Agent模式兼容的事件格式
 */
@Data
public class GraphEvent {

    /** 事件类型 (小写字符串，与前端兼容) */
    private String type;
    
    /** 节点/Agent名称 */
    @JsonProperty("agent")
    private String agent;
    
    /** 事件消息 */
    private String message;
    
    /** 事件数据 */
    private Object data;
    
    /** 会话ID */
    private String sessionId;
    
    /** 时间戳 (毫秒) */
    private long timestamp;

    public GraphEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    // ========== 事件类型常量 (与前端兼容) ==========
    
    /** 开始执行 */
    public static final String TYPE_START = "start";
    
    /** 执行完成 */
    public static final String TYPE_COMPLETE = "complete";
    
    /** 最终回复 */
    public static final String TYPE_FINAL = "final";
    
    /** 降级处理 */
    public static final String TYPE_FALLBACK = "fallback";
    
    /** 错误 */
    public static final String TYPE_ERROR = "error";
    
    /** 流式输出 */
    public static final String TYPE_STREAM = "stream";

    // ========== 静态工厂方法 ==========

    /**
     * 节点开始事件
     */
    public static GraphEvent start(String agentName, String message, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_START);
        event.setAgent(agentName);
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }

    /**
     * 节点完成事件
     */
    public static GraphEvent complete(String agentName, String message, Object data, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_COMPLETE);
        event.setAgent(agentName);
        event.setMessage(message);
        event.setData(data);
        event.setSessionId(sessionId);
        return event;
    }

    /**
     * 最终回复事件
     */
    public static GraphEvent finalReply(String reply, Object sources, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_FINAL);
        event.setAgent("system");
        event.setMessage(reply);
        event.setData(sources);
        event.setSessionId(sessionId);
        return event;
    }

    /**
     * 降级事件
     */
    public static GraphEvent fallback(String agentName, String message, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_FALLBACK);
        event.setAgent(agentName);
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }

    /**
     * 错误事件
     */
    public static GraphEvent error(String message, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_ERROR);
        event.setAgent("system");
        event.setMessage(message);
        event.setSessionId(sessionId);
        return event;
    }

    /**
     * 流式输出事件
     */
    public static GraphEvent stream(String agentName, String chunk, String sessionId) {
        GraphEvent event = new GraphEvent();
        event.setType(TYPE_STREAM);
        event.setAgent(agentName);
        event.setMessage(chunk);
        event.setSessionId(sessionId);
        return event;
    }
}
