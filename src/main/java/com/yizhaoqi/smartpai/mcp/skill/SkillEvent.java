package com.yizhaoqi.smartpai.mcp.skill;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 技能事件（用于流式返回）
 */
@Data
public class SkillEvent {

    /** 事件类型 */
    private EventType type;
    
    /** 技能名称 */
    private String skillName;
    
    /** 事件数据 */
    private Object data;
    
    /** 消息 */
    private String message;
    
    /** 时间戳 */
    private LocalDateTime timestamp = LocalDateTime.now();

    public enum EventType {
        START,          // 技能开始执行
        PROGRESS,       // 执行进度
        CHUNK,          // 流式输出片段
        RESULT,         // 执行结果
        ERROR,          // 错误
        COMPLETE        // 执行完成
    }

    public static SkillEvent start(String skillName) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.START);
        event.setSkillName(skillName);
        return event;
    }

    public static SkillEvent start(String skillName, String message) {
        SkillEvent event = start(skillName);
        event.setMessage(message);
        return event;
    }

    public static SkillEvent progress(String skillName, String message) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.PROGRESS);
        event.setSkillName(skillName);
        event.setMessage(message);
        return event;
    }

    public static SkillEvent chunk(String skillName, String chunk) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.CHUNK);
        event.setSkillName(skillName);
        event.setData(chunk);
        return event;
    }

    public static SkillEvent result(SkillResult result) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.RESULT);
        event.setData(result);
        return event;
    }

    public static SkillEvent error(String skillName, String error) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.ERROR);
        event.setSkillName(skillName);
        event.setMessage(error);
        return event;
    }

    public static SkillEvent complete(String skillName) {
        SkillEvent event = new SkillEvent();
        event.setType(EventType.COMPLETE);
        event.setSkillName(skillName);
        return event;
    }

    public static SkillEvent complete(String skillName, String message) {
        SkillEvent event = complete(skillName);
        event.setMessage(message);
        return event;
    }
}
