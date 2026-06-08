package com.yizhaoqi.smartpai.service.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 系统工具集合
 *
 * 提供 Agent 可调用的系统级工具：
 * - 获取当前时间（避免 LLM 时间幻觉）
 * - 格式化输出
 *
 * 扩展点：可接入 SerpAPI / Bing Search API 实现真实网络搜索
 */
@Component
public class SystemTool {

    private static final Logger log = LoggerFactory.getLogger(SystemTool.class);

    /**
     * 获取当前日期和时间。
     * 当用户询问"今天是几号"、"现在几点"等时间相关问题时使用。
     */
    @Tool("获取当前的日期和时间。当用户询问当前时间、今天日期时使用此工具。")
    public String getCurrentDateTime() {
        String now = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        log.debug("[SystemTool] 获取当前时间: {}", now);
        return "当前时间：" + now;
    }

    /**
     * 当知识库和系统工具都无法回答时，返回诚实的兜底回复。
     * Agent 在确认无法回答时主动调用，避免幻觉。
     */
    @Tool("当无法从知识库或其他工具获取答案时，生成一个诚实的'无法回答'回复。")
    public String generateFallbackReply(
            @P("用户的原始问题") String question) {
        log.info("[SystemTool] 触发兜底回复，问题: {}", question);
        return String.format(
                "抱歉，我在知识库中未找到关于「%s」的相关信息。" +
                "您可以尝试：\n1. 上传相关文档到知识库\n2. 换一种方式描述问题\n3. 联系管理员补充知识库内容",
                question);
    }
}
