package com.yizhaoqi.smartpai.mcp.router;

import com.yizhaoqi.smartpai.mcp.context.McpContext;

/**
 * 前置路由器接口
 * 在进入主流程之前，判断是否需要直接调用工具或返回答案
 * 
 * 使用场景：
 * 1. "今天是周几" -> 直接调用 DateTimeTool 返回
 * 2. "搜索最新的AI新闻" -> 直接调用 WebSearchTool
 * 3. "帮我计算 123*456" -> 直接调用 CalculatorTool
 * 
 * 特点：
 * - 可插拔：通过 Spring @Component 自动注册
 * - 优先级：按 order 排序，先匹配到的先执行
 * - 可配置：可通过配置启用/禁用
 */
public interface PreRouter {

    /**
     * 路由器名称
     */
    String getName();

    /**
     * 路由器描述
     */
    String getDescription();

    /**
     * 判断是否匹配此路由
     * @param context MCP 上下文
     * @return true 表示匹配，应该执行此路由
     */
    boolean matches(McpContext context);

    /**
     * 执行路由逻辑
     * @param context MCP 上下文
     * @return 路由结果
     */
    RouteResult route(McpContext context);

    /**
     * 优先级（数值越小优先级越高）
     * 默认 100，建议范围 1-1000
     */
    default int getOrder() {
        return 100;
    }

    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }
}
