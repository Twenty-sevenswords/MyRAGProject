package com.yizhaoqi.smartpai.mcp.function;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 函数执行结果
 */
@Data
public class FunctionResult {

    /** 函数调用ID */
    private String callId;

    /** 函数名称 */
    private String name;

    /** 执行状态 */
    private ExecutionStatus status;

    /** 返回结果（JSON 字符串） */
    private String result;

    /** 错误信息 */
    private String error;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 执行时间 */
    private LocalDateTime timestamp = LocalDateTime.now();

    public enum ExecutionStatus {
        SUCCESS,
        FAILURE,
        TIMEOUT
    }

    public static FunctionResult success(String callId, String name, String result) {
        FunctionResult fr = new FunctionResult();
        fr.setCallId(callId);
        fr.setName(name);
        fr.setStatus(ExecutionStatus.SUCCESS);
        fr.setResult(result);
        return fr;
    }

    public static FunctionResult failure(String callId, String name, String error) {
        FunctionResult fr = new FunctionResult();
        fr.setCallId(callId);
        fr.setName(name);
        fr.setStatus(ExecutionStatus.FAILURE);
        fr.setError(error);
        return fr;
    }
}
