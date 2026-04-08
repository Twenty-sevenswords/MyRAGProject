package com.yizhaoqi.smartpai.mcp.function;

import lombok.Data;
import java.util.Map;

/**
 * Function Calling 标准接口
 * 遵循 OpenAI Function Calling 协议
 */
@Data
public class FunctionDefinition {

    /** 函数名称 */
    private String name;

    /** 函数描述 */
    private String description;

    /** 参数定义（JSON Schema 格式） */
    private Map<String, Object> parameters;

    /** 是否必须调用 */
    private boolean required;

    public static FunctionDefinition create(String name, String description) {
        FunctionDefinition def = new FunctionDefinition();
        def.setName(name);
        def.setDescription(description);
        return def;
    }

    public FunctionDefinition parameters(Map<String, Object> params) {
        this.parameters = params;
        return this;
    }

    public FunctionDefinition required(boolean required) {
        this.required = required;
        return this;
    }
}
