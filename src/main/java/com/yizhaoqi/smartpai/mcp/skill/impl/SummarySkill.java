package com.yizhaoqi.smartpai.mcp.skill.impl;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.skill.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 摘要技能
 * 对长文本或多个文档进行总结
 */
@Component
public class SummarySkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(SummarySkill.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Override
    public String getName() {
        return "summarize";
    }

    @Override
    public String getDescription() {
        return "对文本内容进行总结摘要。当用户需要总结、概括、提取要点时使用此技能。";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
                .property("text", SkillParameterSchema.PropertySchema.string("需要总结的文本").required(true))
                .property("style", SkillParameterSchema.PropertySchema.string("摘要风格")
                        .enums("brief", "detailed", "bullet").defaultValue("brief"))
                .property("maxLength", SkillParameterSchema.PropertySchema.integer("最大长度").defaultValue(500))
                .required("text");
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.GENERATION;
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public boolean shouldInvoke(McpContext context) {
        String intent = context.getIntent();
        return "SUMMARIZE".equals(intent);
    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String text = params.getString("text");
        String style = params.getString("style", "brief");
        int maxLength = params.getInteger("maxLength", 500);

        if (text == null || text.isEmpty()) {
            // 尝试从上下文获取
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = 
                    (List<Map<String, Object>>) context.getSkillResult("sources");
            if (sources != null && !sources.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> src : sources) {
                    sb.append(src.get("content")).append("\n\n");
                }
                text = sb.toString();
            }
        }

        if (text == null || text.isEmpty()) {
            return SkillResult.failure("NO_TEXT", "没有可总结的文本内容");
        }

        try {
            String summary = generateSummary(text, style, maxLength);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("summary", summary);
            data.put("style", style);
            data.put("originalLength", text.length());

            return SkillResult.success(data, "摘要生成完成");

        } catch (Exception e) {
            logger.error("[SummarySkill] 执行失败", e);
            return SkillResult.failure("EXECUTION_ERROR", e.getMessage());
        }
    }

    /**
     * 生成摘要
     */
    private String generateSummary(String text, String style, int maxLength) {
        String stylePrompt;
        switch (style) {
            case "detailed":
                stylePrompt = "请生成详细的摘要，包含主要内容和关键细节。";
                break;
            case "bullet":
                stylePrompt = "请用要点列表形式总结，每个要点一行，以 • 开头。";
                break;
            default:
                stylePrompt = "请生成简洁的摘要，突出核心要点。";
        }

        String prompt = String.format(
            "%s\n\n原文内容：\n%s\n\n要求：\n1. 摘要长度不超过 %d 字\n2. 使用简体中文\n3. 突出重点信息",
            stylePrompt, text.substring(0, Math.min(text.length(), 10000)), maxLength
        );

        return deepSeekClient.chat(prompt, "你是一个专业的文本摘要助手，擅长提取关键信息。");
    }
}
