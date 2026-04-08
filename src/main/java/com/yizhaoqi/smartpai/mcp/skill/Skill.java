package com.yizhaoqi.smartpai.mcp.skill;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import reactor.core.publisher.Flux;

/**
 * Skill 插件接口
 * 所有能力抽象为可插拔的 Skill
 */
public interface Skill {

    /**
     * 获取技能名称（唯一标识）
     */
    String getName();

    /**
     * 获取技能描述
     */
    String getDescription();

    /**
     * 获取技能参数定义（JSON Schema 格式，用于 Function Calling）
     */
    SkillParameterSchema getParameterSchema();

    /**
     * 执行技能（同步）
     * @param context MCP 上下文
     * @param params 技能参数
     * @return 执行结果
     */
    SkillResult execute(McpContext context, SkillParams params);

    /**
     * 执行技能（流式）
     * @param context MCP 上下文
     * @param params 技能参数
     * @return 事件流
     */
    default Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        return Flux.just(SkillEvent.result(execute(context, params)));
    }

    /**
     * 是否支持流式执行
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * 技能优先级（数值越小优先级越高）
     */
    default int getPriority() {
        return 100;
    }

    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 技能类别
     */
    default SkillCategory getCategory() {
        return SkillCategory.TOOL;
    }

    /**
     * 判断是否应该调用此技能
     * @param context MCP 上下文
     * @return true 表示应该调用
     */
    default boolean shouldInvoke(McpContext context) {
        return true;
    }
}
