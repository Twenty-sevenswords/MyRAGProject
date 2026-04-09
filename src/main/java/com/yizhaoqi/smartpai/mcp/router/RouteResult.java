package com.yizhaoqi.smartpai.mcp.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 路由结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteResult {

    /**
     * 路由类型
     */
    public enum RouteType {
        /** 直接返回答案，跳过后续流程 */
        DIRECT_ANSWER,
        
        /** 调用指定工具后返回 */
        CALL_TOOL,
        
        /** 继续正常流程（RAG等） */
        CONTINUE,
        
        /** 需要更多信息 */
        NEED_MORE_INFO
    }

    /**
     * 路由类型
     */
    private RouteType type;

    /**
     * 直接返回的答案（type=DIRECT_ANSWER 时使用）
     */
    private String answer;

    /**
     * 要调用的工具名称（type=CALL_TOOL 时使用）
     */
    private String toolName;

    /**
     * 工具参数（type=CALL_TOOL 时使用）
     */
    private Map<String, Object> toolParams;

    /**
     * 来源信息（可选）
     */
    private List<Map<String, Object>> sources;

    /**
     * 路由原因说明
     */
    private String reason;

    /**
     * 置信度 (0-1)
     */
    private double confidence;

    // ========== 静态工厂方法 ==========

    /**
     * 创建直接返回答案的结果
     */
    public static RouteResult directAnswer(String answer, String reason) {
        return RouteResult.builder()
                .type(RouteType.DIRECT_ANSWER)
                .answer(answer)
                .reason(reason)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建直接返回答案的结果（带来源）
     */
    public static RouteResult directAnswer(String answer, String reason, List<Map<String, Object>> sources) {
        return RouteResult.builder()
                .type(RouteType.DIRECT_ANSWER)
                .answer(answer)
                .reason(reason)
                .sources(sources)
                .confidence(1.0)
                .build();
    }

    /**
     * 创建调用工具的结果
     */
    public static RouteResult callTool(String toolName, Map<String, Object> params, String reason) {
        return RouteResult.builder()
                .type(RouteType.CALL_TOOL)
                .toolName(toolName)
                .toolParams(params)
                .reason(reason)
                .confidence(0.9)
                .build();
    }

    /**
     * 创建继续正常流程的结果
     */
    public static RouteResult continueFlow(String reason) {
        return RouteResult.builder()
                .type(RouteType.CONTINUE)
                .reason(reason)
                .confidence(0.5)
                .build();
    }

    /**
     * 创建需要更多信息的结果
     */
    public static RouteResult needMoreInfo(String reason) {
        return RouteResult.builder()
                .type(RouteType.NEED_MORE_INFO)
                .reason(reason)
                .confidence(0.8)
                .build();
    }
}
