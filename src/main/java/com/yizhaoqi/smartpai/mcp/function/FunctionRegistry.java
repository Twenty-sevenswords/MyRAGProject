package com.yizhaoqi.smartpai.mcp.function;

import com.yizhaoqi.smartpai.mcp.skill.Skill;
import com.yizhaoqi.smartpai.mcp.skill.SkillParameterSchema;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 函数注册中心
 * 管理所有可调用的 Function Calling 函数
 */
@Component
public class FunctionRegistry {

    /** 函数定义映射 */
    private final Map<String, FunctionDefinition> definitions = new ConcurrentHashMap<>();

    /** 函数与 Skill 的映射 */
    private final Map<String, Skill> skillMap = new ConcurrentHashMap<>();

    /**
     * 注册 Skill 为可调用的函数
     */
    public void registerSkill(Skill skill) {
        String name = skill.getName();
        
        // 创建函数定义
        FunctionDefinition definition = FunctionDefinition.create(name, skill.getDescription());
        
        // 设置参数
        SkillParameterSchema schema = skill.getParameterSchema();
        if (schema != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", schema.getType());
            params.put("properties", schema.getProperties());
            if (schema.getRequired() != null) {
                params.put("required", schema.getRequired());
            }
            definition.setParameters(params);
        }

        definitions.put(name, definition);
        skillMap.put(name, skill);
    }

    /**
     * 获取函数定义
     */
    public FunctionDefinition getDefinition(String name) {
        return definitions.get(name);
    }

    /**
     * 获取 Skill
     */
    public Skill getSkill(String name) {
        return skillMap.get(name);
    }

    /**
     * 获取所有函数定义（用于 LLM Function Calling）
     */
    public List<FunctionDefinition> getAllDefinitions() {
        return new ArrayList<>(definitions.values());
    }

    /**
     * 获取所有函数定义（OpenAI 格式）
     */
    public List<Map<String, Object>> getOpenAiFunctionDefinitions() {
        List<Map<String, Object>> functions = new ArrayList<>();
        for (FunctionDefinition def : definitions.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.getName());
            function.put("description", def.getDescription());
            function.put("parameters", def.getParameters());
            functions.add(function);
        }
        return functions;
    }

    /**
     * 检查函数是否存在
     */
    public boolean hasFunction(String name) {
        return definitions.containsKey(name);
    }

    /**
     * 获取所有已注册的函数名称
     */
    public Set<String> getFunctionNames() {
        return new HashSet<>(definitions.keySet());
    }
}
