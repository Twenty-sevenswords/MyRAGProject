package com.yizhaoqi.smartpai.mcp.skill.impl;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.skill.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 联网搜索技能
 * 当本地 RAG 无结果时自动 fallback 到联网搜索
 */
@Component
public class SearchSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(SearchSkill.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Value("${ai.web-search.enabled:true}")
    private boolean webSearchEnabled;

    @Value("${ai.web-search.api-key:}")
    private String searchApiKey;

    @Value("${ai.web-search.engine:bing}")
    private String searchEngine;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "联网搜索获取实时信息。当本地知识库无结果或需要最新信息时使用此技能。";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
                .property("query", SkillParameterSchema.PropertySchema.string("搜索关键词").required(true))
                .property("count", SkillParameterSchema.PropertySchema.integer("返回结果数量").defaultValue(5))
                .required("query");
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.RETRIEVAL;
    }

    @Override
    public int getPriority() {
        return 20; // RAG 失败后的备选
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean shouldInvoke(McpContext context) {
        // 当 RAG 无结果或需要联网搜索时触发
        Boolean ragFailed = context.getSkillResult("rag_failed");
        return context.isNeedWebSearch() ||
                Boolean.TRUE.equals(ragFailed) ||
                (context.getFinalReply() == null || context.getFinalReply().trim().isEmpty());

    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        if (!webSearchEnabled) {
            return SkillResult.failure("DISABLED", "联网搜索功能已禁用");
        }

        String query = params.getString("message", context.getUserMessage());

        try {
            logger.info("[SearchSkill] 执行联网搜索: {}", query);

            // 模拟联网搜索（实际可对接 Bing/Google/SerpAPI）
            List<Map<String, Object>> searchResults = performWebSearch(query);

            if (searchResults.isEmpty()) {
                return SkillResult.failure("NO_RESULTS", "联网搜索无结果");
            }

            // 使用 LLM 总结搜索结果
            String summary = summarizeResults(query, searchResults);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reply", summary);
            data.put("sources", searchResults);
            data.put("usedWebSearch", true);

            return SkillResult.success(data, "联网搜索完成");

        } catch (Exception e) {
            logger.error("[SearchSkill] 执行失败", e);
            return SkillResult.failure("EXECUTION_ERROR", e.getMessage());
        }
    }

    @Override
    public Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        return Flux.create(emitter -> {
            try {
                emitter.next(SkillEvent.start(getName(), "开始联网搜索..."));

                String query = params.getString("message", context.getUserMessage());
                emitter.next(SkillEvent.progress(getName(), "搜索: " + query));

                List<Map<String, Object>> searchResults = performWebSearch(query);
                logger.info("[SearchSkill] 搜索结果: {} 条", searchResults.size());

                if (searchResults.isEmpty()) {
                    emitter.next(SkillEvent.error(getName(), "联网搜索无结果"));
                    emitter.complete();
                    return;
                }

                // 流式生成摘要
                emitter.next(SkillEvent.progress(getName(), "生成摘要..."));
                String summary = summarizeResults(query, searchResults);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("reply", summary);
                data.put("sources", searchResults);

                emitter.next(SkillEvent.result(SkillResult.success(data)));
                emitter.next(SkillEvent.complete(getName()));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[SearchSkill] 流式执行失败", e);
                emitter.next(SkillEvent.error(getName(), e.getMessage()));
                emitter.complete();
            }
        });
    }

    /**
     * 执行联网搜索
     * 可对接：Bing Search API / SerpAPI / Google Custom Search
     */
    private List<Map<String, Object>> performWebSearch(String query) {
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            // TODO: 实际对接搜索 API
            // 这里使用模拟数据，实际应调用搜索 API
            
            // 模拟搜索结果
            for (int i = 1; i <= 3; i++) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("title", "搜索结果 " + i + ": " + query);
                result.put("url", "https://example.com/result/" + i);
                result.put("snippet", "这是关于 \"" + query + "\" 的搜索结果摘要内容...");
                result.put("source", "web");
                results.add(result);
            }

            logger.debug("[SearchSkill] 模拟返回 {} 条搜索结果", results.size());

        } catch (Exception e) {
            logger.error("[SearchSkill] 搜索调用失败", e);
        }

        return results;
    }

    /**
     * 使用 LLM 总结搜索结果
     */
    private String summarizeResults(String query, List<Map<String, Object>> results) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);
            context.append("【结果").append(i + 1).append("】\n");
            context.append("标题: ").append(r.get("title")).append("\n");
            context.append("摘要: ").append(r.get("snippet")).append("\n\n");
        }

        String prompt = String.format(
            "基于以下搜索结果回答用户问题。\n\n搜索结果：\n%s\n\n问题：%s\n\n要求：综合所有搜索结果，给出准确、有用的回答。",
            context.toString(), query
        );

        return deepSeekClient.chat(prompt, "你是一个有帮助的助手，擅长从搜索结果中提取有用信息。");
    }
}
