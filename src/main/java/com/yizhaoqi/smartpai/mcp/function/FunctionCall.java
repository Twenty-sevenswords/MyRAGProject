package com.yizhaoqi.smartpai.mcp.function;

import lombok.Data;
import java.util.Map;

/**
 * LLM 返回的函数调用请求
 */
@Data
public class FunctionCall {

    /** 函数名称 */
    private String name;

    /** 函数参数（JSON 字符串） */
    private String arguments;

    /** 函数调用ID */
    private String callId;

    public static FunctionCall create(String name, String arguments) {
        FunctionCall call = new FunctionCall();
        call.setName(name);
        call.setArguments(arguments);
        call.setCallId(java.util.UUID.randomUUID().toString());
        return call;
    }

    public static FunctionCall create(String name, Map<String, Object> args) {
        FunctionCall call = new FunctionCall();
        call.setName(name);
        try {
            call.setArguments(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args));
        } catch (Exception e) {
            call.setArguments("{}");
        }
        call.setCallId(java.util.UUID.randomUUID().toString());
        return call;
    }
}
