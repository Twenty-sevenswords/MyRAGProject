package com.yizhaoqi.smartpai.mcp.skill.impl;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.skill.*;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 问答技能
 * 直接使用 LLM 回答问题
 */
@Component
public class QaSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(QaSkill.class);

    @Autowired
    private LangChain4jChatService chatService;

    @Override
    public String getName() {
        return "qa";
    }

    @Override
    public String getDescription() {
        return "使用 LLM 直接回答问题。适用于通用问答、闲聊、知识推理等场景。";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
                .property("question", SkillParameterSchema.PropertySchema.string("用户问题").required(true))
                .property("context", SkillParameterSchema.PropertySchema.string("上下文信息"))
                .property("history", SkillParameterSchema.PropertySchema.array("对话历史", "object"))
                .required("question");
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.GENERATION;
    }

    @Override
    public int getPriority() {
        return 50; // 较低优先级，作为兜底
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean shouldInvoke(McpContext context) {
        String intent = context.getIntent();
        return "QA".equals(intent) || "CHAT".equals(intent) || "UNKNOWN".equals(intent);
    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String question = params.getString("message", context.getUserMessage());
        String contextInfo = params.getString("context");

        try {
            // 构建对话历史
            List<McpContext.ChatMessage> history = context.getRecentHistory(5);
            String historyText = formatHistory(history);

            // 构建提示
            String prompt = buildPrompt(question, contextInfo, historyText);

            // 调用 LLM
            String answer = chatService.chat("你是派聪明知识助手，请用简体中文回答问题。", prompt);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reply", answer);
            data.put("usedLLM", true);

            return SkillResult.success(data, "问答完成");

        } catch (Exception e) {
            logger.error("[QaSkill] 执行失败", e);
            return SkillResult.failure("EXECUTION_ERROR", e.getMessage());
        }
    }

    @Override
    public Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        return Flux.create(emitter -> {
            try {
                emitter.next(SkillEvent.start(getName(), "思考中..."));

                String question = params.getString("message", context.getUserMessage());
                String contextInfo = params.getString("context");
                List<McpContext.ChatMessage> history = context.getRecentHistory(5);
                String historyText = formatHistory(history);

                String prompt = buildPrompt(question, contextInfo, historyText);

                // 使用同步调用
                String answer = chatService.chat("你是派聪明知识助手，请用简体中文回答问题。", prompt);
                
                // 模拟流式输出
                for (int i = 0; i < answer.length(); i += 10) {
                    int end = Math.min(i + 10, answer.length());
                    emitter.next(SkillEvent.chunk(getName(), answer.substring(i, end)));
                }

                emitter.next(SkillEvent.complete(getName()));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[QaSkill] 流式执行失败", e);
                emitter.next(SkillEvent.error(getName(), e.getMessage()));
                emitter.complete();
            }
        });
    }

    private String formatHistory(List<McpContext.ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpContext.ChatMessage msg : history) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String question, String context, String history) {
        StringBuilder prompt = new StringBuilder();

        if (history != null && !history.isEmpty()) {
            prompt.append("【对话历史】\n").append(history).append("\n");
        }

        if (context != null && !context.isEmpty()) {
            prompt.append("【参考信息】\n").append(context).append("\n\n");
        }

        prompt.append("【用户问题】\n").append(question);

        return prompt.toString();
    }
}
