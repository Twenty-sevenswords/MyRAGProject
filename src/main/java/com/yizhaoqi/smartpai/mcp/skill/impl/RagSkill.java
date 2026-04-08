package com.yizhaoqi.smartpai.mcp.skill.impl;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.skill.*;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索技能
 * 本地知识库检索 + LLM 生成
 */
@Component
public class RagSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(RagSkill.class);

    @Autowired
    private HybridSearchService searchService;

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Value("${ai.prompt.rules:}")
    private String systemPrompt;

    @Value("${ai.generation.temperature:0.3}")
    private double temperature;

    @Value("${ai.generation.max-tokens:2000}")
    private int maxTokens;

    @Override
    public String getName() {
        return "rag_search";
    }

    @Override
    public String getDescription() {
        return "从本地知识库检索相关文档并生成回答。当用户询问与知识库相关的问题时使用此技能。";
    }

    @Override
    public SkillParameterSchema getParameterSchema() {
        return SkillParameterSchema.create()
                .property("query", SkillParameterSchema.PropertySchema.string("搜索查询语句").required(true))
                .property("topK", SkillParameterSchema.PropertySchema.integer("返回结果数量").defaultValue(5))
                .property("useLLM", SkillParameterSchema.PropertySchema.bool("是否使用LLM生成").defaultValue(true))
                .required("query");
    }

    @Override
    public SkillCategory getCategory() {
        return SkillCategory.RETRIEVAL;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean shouldInvoke(McpContext context) {
        String intent = context.getIntent();
        return "SEARCH".equals(intent) || "QA".equals(intent) || 
               "COMPARE".equals(intent) || "SUMMARIZE".equals(intent) || 
               "CHAT".equals(intent);
    }

    @Override
    public SkillResult execute(McpContext context, SkillParams params) {
        String query = params.getString("message", context.getUserMessage());
        int topK = params.getInteger("topK", 5);
        String userId = context.getUserId();

        try {
            // 1. 执行检索
            List<SearchResult> results = searchService.searchWithPermission(query, userId, topK);
            logger.info("[RagSkill] 检索结果: {} 条", results.size());

            if (results.isEmpty()) {
                return SkillResult.failure("NO_RESULTS", "未找到相关文档");
            }

            // 2. 构建 context
            String contextText = buildContext(results);

            // 3. 生成回答
            String reply = generateReply(query, contextText, context);

            // 4. 构建返回结果
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reply", reply);
            data.put("sources", buildSources(results));
            data.put("usedLLM", true);
            data.put("resultCount", results.size());

            return SkillResult.success(data, "RAG 检索完成");

        } catch (Exception e) {
            logger.error("[RagSkill] 执行失败", e);
            return SkillResult.failure("EXECUTION_ERROR", e.getMessage());
        }
    }

    @Override
    public Flux<SkillEvent> executeStream(McpContext context, SkillParams params) {
        return Flux.create(emitter -> {
            try {
                emitter.next(SkillEvent.start(getName(), "开始 RAG 检索..."));

                String query = params.getString("message", context.getUserMessage());
                int topK = params.getInteger("topK", 5);
                String userId = context.getUserId();

                // 1. 执行检索
                emitter.next(SkillEvent.progress(getName(), "检索知识库..."));
                List<SearchResult> results = searchService.searchWithPermission(query, userId, topK);
                logger.info("[RagSkill] 检索结果: {} 条", results.size());

                if (results.isEmpty()) {
                    emitter.next(SkillEvent.error(getName(), "未找到相关文档"));
                    emitter.complete();
                    return;
                }

                // 2. 构建 context
                String contextText = buildContext(results);

                // 3. 流式生成回答
                emitter.next(SkillEvent.progress(getName(), "生成回答..."));

                StringBuilder fullReplyBuilder = new StringBuilder();

                // 使用流式 LLM
                String prompt = buildPrompt(query, contextText);
//                deepSeekClient.streamChat(prompt, systemPrompt, chunk -> {
//                    emitter.next(SkillEvent.chunk(getName(), chunk));
//                });
                deepSeekClient.streamResponse(
                        prompt,
                        systemPrompt,
                        null,
                        chunk -> {
                            fullReplyBuilder.append(chunk);
                            emitter.next(SkillEvent.chunk(getName(), chunk));
                        },
                        err -> logger.error("流式输出错误", err)
                );

                String fullReply = fullReplyBuilder.toString();

                // 4. 构建返回结果
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("reply", fullReply);
                data.put("sources", buildSources(results));
                data.put("usedLLM", true);

                emitter.next(SkillEvent.result(SkillResult.success(data)));
                emitter.next(SkillEvent.complete(getName(), "RAG 检索完成"));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[RagSkill] 流式执行失败", e);
                emitter.next(SkillEvent.error(getName(), e.getMessage()));
                emitter.complete();
            }
        });
    }

    private String buildContext(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("【文档").append(i + 1).append(": ").append(r.getFileName()).append("】\n");
            sb.append(r.getTextContent()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String query, String context) {
        return String.format(
            "基于以下文档片段回答问题：\n\n%s\n\n问题：%s\n\n要求：\n1. 直接回答问题\n2. 引用文档内容时标注来源，格式为【来源#编号: 文件名】\n3. 若无足够信息，请说明",
            context, query
        );
    }

    private String generateReply(String query, String context, McpContext ctx) {
        String prompt = buildPrompt(query, context);
        return deepSeekClient.chat(prompt, systemPrompt);
    }

    private List<Map<String, Object>> buildSources(List<SearchResult> results) {
        return results.stream().map(r -> {
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("id", r.getFileMd5() + "_" + r.getChunkId());
            src.put("fileName", r.getFileName());
            src.put("content", r.getTextContent().length() > 200 ?
                    r.getTextContent().substring(0, 200) + "..." : r.getTextContent());
            src.put("score", r.getScore());
            return src;
        }).collect(Collectors.toList());
    }
}
