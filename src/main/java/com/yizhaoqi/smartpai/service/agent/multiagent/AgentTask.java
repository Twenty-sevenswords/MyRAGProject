package com.yizhaoqi.smartpai.service.agent.multiagent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 子任务模型
 *
 * 路由规划 Agent 将用户请求拆解为多个子任务，
 * 每个子任务发布到对应的 Kafka Topic，由专属 Agent 消费执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {

    /** 子任务唯一ID */
    private String taskId;

    /** 关联的主任务ID（用于结果汇总） */
    private String correlationId;

    /** 任务类型 */
    private TaskType type;

    /** 用户原始问题 */
    private String question;

    /** 检索关键词（由路由规划Agent提取） */
    private List<String> keywords;

    /** 用户ID（权限过滤用） */
    private String userId;

    /** 会话ID */
    private String sessionId;

    /** 任务优先级（1-5，越大越优先） */
    private int priority;

    /** 扩展参数 */
    private Map<String, Object> params;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 总子任务数（用于判断是否全部完成） */
    private int totalSubTasks;

    public enum TaskType {
        /** 向量语义检索 */
        VECTOR_RETRIEVAL,
        /** 关键词全文检索 */
        KEYWORD_RETRIEVAL,
        /** 反思评估（对检索结果质量打分） */
        REFLECTION,
        /** 汇总生成最终回复 */
        SUMMARY
    }
}
