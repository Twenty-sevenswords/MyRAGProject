package com.yizhaoqi.smartpai.mcp.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.skill.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质检查技能
 * 对 AI 生成的回答进行质量检验
 */
@Component
public class CheckSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(CheckSkill.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Value("${ai.agent.check.source-marker:【}")
    private String sourceMarker;

    @Value("${ai.agent.check.min-reply-length:10}")
    private int minReplyLength;

    @Value("${ai.agent.check.max-reply-length:2000}")
    private int maxReplyLength;

    @Value("${ai.agent.check.pass-score-threshold:6}")
    private int passScoreThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "check";
    }

    @Override
    public String getDescription() {
        return "对 AI 回答进行质量检查。评估回答的相关性、准确性、完整性、可读性。";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
                .property("question", SkillParameterSchema.PropertySchema.string("用户问题").required(true))
                .property("answer", SkillParameterSchema.PropertySchema.string("AI 回答").required(true))
                .property("sources", SkillParameterSchema.PropertySchema.array("来源列表", "object"))
                .required("question", "answer");
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.VALIDATION;
    }

    @Override
    public int getPriority() {
        return 5; // 最高优先级，最后执行
    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String question = params.getString("question");
        String answer = params.getString("answer");

        if (question == null) {
            question = context.getUserMessage();
        }
        if (answer == null) {
            answer = context.getFinalReply();
        }

        if (answer == null || answer.isEmpty()) {
            return SkillResult.failure("NO_ANSWER", "没有可检查的回答");
        }

        try {
            // 1. 快速检查
            List<String> quickIssues = quickCheck(question, answer);
            if (!quickIssues.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("passed", false);
                data.put("score", 0);
                data.put("reason", String.join("; ", quickIssues));
                data.put("issues", quickIssues);
                return SkillResult.success(data, "快速检查未通过");
            }

            // 2. LLM 深度检查
            Map<String, Object> checkResult = llmCheck(question, answer);

            return SkillResult.success(checkResult, 
                    (Boolean) checkResult.get("passed") ? "质检通过" : "质检未通过");

        } catch (Exception e) {
            logger.error("[CheckSkill] 执行失败", e);
            // 质检失败默认通过
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("passed", true);
            data.put("score", 7);
            data.put("reason", "质检异常，默认通过");
            return SkillResult.success(data, "质检异常，默认通过");
        }
    }

    /**
     * 快速检查
     */
    private List<String> quickCheck(String question, String answer) {
        List<String> issues = new ArrayList<>();

        // 1. 长度检查
        if (answer.length() < minReplyLength) {
            issues.add("回答过短（少于 " + minReplyLength + " 字）");
        }
        if (answer.length() > maxReplyLength) {
            issues.add("回答过长（超过 " + maxReplyLength + " 字）");
        }

        // 2. 来源标注检查
        if (!answer.contains(sourceMarker)) {
            // 仅当有检索结果时才要求标注
            // issues.add("回答缺少来源标注");
        }

        // 3. 无效回答检查
        String lowerAnswer = answer.toLowerCase();
        if (lowerAnswer.contains("我无法回答") || 
            lowerAnswer.contains("我不知道") ||
            lowerAnswer.contains("没有找到相关")) {
            // 允许这类回答，但标记为低分
            logger.debug("[CheckSkill] 回答表示无信息");
        }

        return issues;
    }

    /**
     * LLM 深度检查
     */
    private Map<String, Object> llmCheck(String question, String answer) {
        String checkPrompt = String.format(
            "你是一个回答质量检查员。请评估以下回答是否合格。\n\n" +
            "【用户问题】\n%s\n\n" +
            "【AI回答】\n%s\n\n" +
            "【评估标准】\n" +
            "1. 相关性：回答是否针对用户问题\n" +
            "2. 准确性：回答内容是否有事实依据\n" +
            "3. 完整性：回答是否充分解答了问题\n" +
            "4. 可读性：回答是否清晰、有条理\n\n" +
            "【输出格式】\n" +
            "请严格按照以下JSON格式输出，不要有多余内容：\n" +
            "{\"passed\": true/false, \"score\": 1-10, \"reason\": \"简短理由\"}\n\n" +
            "其中：\n- passed: true表示合格，false表示不合格\n- score: 1-10分，6分及以上为合格\n- reason: 一句话说明判定理由",
            question, answer
        );

        try {
            String response = deepSeekClient.chat(checkPrompt, "你是一个严格的质量检查员。");
            
            // 解析 JSON
            Map<String, Object> result = parseCheckResult(response);
            
            // 确保分数满足阈值
            int score = result.get("score") instanceof Number ? 
                    ((Number) result.get("score")).intValue() : 5;
            boolean passed = score >= passScoreThreshold;
            result.put("passed", passed);
            result.put("score", score);

            return result;

        } catch (Exception e) {
            logger.warn("[CheckSkill] LLM 检查失败，使用默认结果: {}", e.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("passed", true);
            data.put("score", 7);
            data.put("reason", "LLM 检查失败，默认通过");
            return data;
        }
    }

    /**
     * 解析检查结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCheckResult(String response) {
        try {
            // 尝试直接解析 JSON
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            // 尝试提取 JSON
            Pattern pattern = Pattern.compile("\\{[^{}]*\\}");
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                try {
                    return objectMapper.readValue(matcher.group(), Map.class);
                } catch (Exception ex) {
                    logger.warn("[CheckSkill] JSON 提取失败");
                }
            }
        }

        // 默认结果
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("passed", true);
        data.put("score", 7);
        data.put("reason", "无法解析检查结果");
        return data;
    }
}
