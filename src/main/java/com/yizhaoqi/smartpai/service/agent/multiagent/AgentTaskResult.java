package com.yizhaoqi.smartpai.service.agent.multiagent;

import com.yizhaoqi.smartpai.dto.SearchResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 子任务执行结果
 *
 * 每个 Agent 执行完子任务后，将结果发布到 agent.retrieval.results Topic，
 * 由 AgentStateManager 汇总到 Redis 共享状态树。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskResult {

    /** 对应的子任务ID */
    private String taskId;

    /** 关联的主任务ID */
    private String correlationId;

    /** 执行的任务类型 */
    private AgentTask.TaskType taskType;

    /** 是否成功 */
    private boolean success;

    /** 检索到的文档片段（检索类任务） */
    private List<SearchResult> searchResults;

    /** 反思评估分数（0-100，反思任务） */
    private int reflectionScore;

    /** 反思评估原因 */
    private String reflectionReason;

    /** 最终生成的回复（汇总任务） */
    private String generatedReply;

    /** 错误信息 */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
