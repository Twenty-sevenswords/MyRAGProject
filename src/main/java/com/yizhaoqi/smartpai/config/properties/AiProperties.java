package com.yizhaoqi.smartpai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 * 所有提示词模板均在 application.yml 中配置，代码中不包含任何硬编码提示词。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();
    private Agent agent = new Agent();

    @Data
    public static class Prompt {
        /** 规则文案（在 application.yml 中配置） */
        private String rules;
        /** 引用开始分隔符（在 application.yml 中配置） */
        private String refStart;
        /** 引用结束分隔符（在 application.yml 中配置） */
        private String refEnd;
        /** 无检索结果时的占位文案（在 application.yml 中配置） */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature ;
        /** 最大输出 tokens */
        private Integer maxTokens ;
        /** nucleus top-p */
        private Double topP ;
    }

    /**
     * Agent 相关配置
     */
    @Data
    public static class Agent {
        private IntentPrompt intent = new IntentPrompt();
        private WorkPrompt work = new WorkPrompt();
        private CheckPrompt check = new CheckPrompt();
    }

    @Data
    public static class IntentPrompt {
        /** 意图分析模板，使用 {message} 占位符（在 application.yml 中配置） */
        private String template;
    }

    @Data
    public static class WorkPrompt {
        /** RAG生成模板，使用 {context} 和 {message} 占位符（在 application.yml 中配置） */
        private String template;
        /** 简单检索结果模板（在 application.yml 中配置） */
        private String simpleSearchTemplate;
        /** 来源标注格式（在 application.yml 中配置） */
        private String sourceFormat;
    }

    @Data
    public static class CheckPrompt {
        /** 质量检查模板，使用 {question} 和 {answer} 占位符（在 application.yml 中配置） */
        private String template;
        /** 来源标注符号（在 application.yml 中配置） */
        private String sourceMarker;
        /** 最小回复长度（在 application.yml 中配置） */
        private int minReplyLength ;
        /** 最大回复长度，用于降级检查（在 application.yml 中配置） */
        private int maxReplyLength ;
        /** 合格分数阈值（在 application.yml 中配置） */
        private int passScoreThreshold ;
    }

}
