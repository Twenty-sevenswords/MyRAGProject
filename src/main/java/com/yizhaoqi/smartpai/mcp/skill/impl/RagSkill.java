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
import org.springframework.data.redis.connection.convert.MapConverter;
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
        //获取历史消息用于上下文
        @SuppressWarnings("unchecked")
        List<McpContext.ChatMessage> history =params.get("history");
        try {
            logger.info("[RagSkill] 执行检索，查询语句: query={},userId={},historySize={}", query,userId,history!=null?history.size():0);

            // 1. 执行检索
            List<SearchResult> results = searchService.searchWithPermission(query, userId, topK);
            logger.info("[RagSkill] 检索结果: {} 条", results.size());

            if (results.isEmpty()) {
                //设置失败标记，以便触发web_search fallback
                context.putSkillResult("rag_failed",true);
                return SkillResult.failure("NO_RESULTS", "未找到相关文档");
            }

            // 2. 构建 context
            String contextText = buildContext(results);

            // 3. 生成回答包含历史
            String reply = generateReply(query, contextText, history);

            // 4. 构建返回结果
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reply", reply);
            data.put("sources", buildSources(results));
            data.put("usedLLM", true);
            data.put("resultCount", results.size());

            return SkillResult.success(data, "RAG 检索完成");

        } catch (Exception e) {
            logger.error("[RagSkill] 执行失败", e);
            //设置失败标记，以便触发web_search fallback
            context.putSkillResult("rag_failed",true);
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

                @SuppressWarnings("unchecked")
                List<McpContext.ChatMessage> history = params.get("history");

                logger.info("[RagSkill] 开始执行流式RAG检索, query={}, userId={}, historySize={}",
                        query, userId, history != null ? history.size() : 0);

                // 1. 执行检索
                emitter.next(SkillEvent.progress(getName(), "检索知识库..."));
                List<SearchResult> results = searchService.searchWithPermission(query, userId, topK);
                logger.info("[RagSkill] 检索结果: {} 条", results.size());

                if (results.isEmpty()) {
                    //设置失败标记，以便触发web_search fallback
                    context.putSkillResult("rag_failed",true);
                    emitter.next(SkillEvent.error(getName(), "未找到相关文档"));
                    emitter.complete();
                    return;
                }

                // 2. 构建 context
                String contextText = buildContext(results);

                // 3. 流式生成回答
                emitter.next(SkillEvent.progress(getName(), "生成回答..."));


                String prompt = buildPromptWithHistory(query, contextText, history);
                logger.debug("[RagSkill] 构建的prompt长度: {}", prompt.length());
                //使用流式代替异步流式调用
                String fullReply = deepSeekClient.chat(prompt,systemPrompt);

                //发送完整的回答啊作为流式事件
                if(fullReply!=null&&!fullReply.isEmpty()){
                    emitter.next(SkillEvent.chunk(getName(), fullReply));
                }

                // 4. 构建返回结果
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("reply", fullReply!=null?fullReply:"");
                data.put("sources", buildSources(results));
                data.put("usedLLM", true);

                logger.info("[RagSkill] 流式RAG检索完成, replyLength={}", fullReply != null ? fullReply.length() : 0);
                emitter.next(SkillEvent.result(SkillResult.success(data)));
                emitter.next(SkillEvent.complete(getName(), "RAG 检索完成"));
                emitter.complete();

            } catch (Exception e) {
                logger.error("[RagSkill] 流式执行失败", e);
                //设置失败标记
                context.putSkillResult("rag_failed",true);
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

    /**
     * 构建带历史对话的prompt
     */
    private String buildPromptWithHistory(String query, String context, List<McpContext.ChatMessage> history) {
        StringBuilder sb = new StringBuilder();

        // 添加历史对话
        if (history != null && !history.isEmpty()) {
            sb.append("【历史对话】\n");
            for (McpContext.ChatMessage msg : history) {
                String role = "user".equals(msg.getRole()) ? "用户" : "助手";
                sb.append(role).append(": ").append(msg.getContent()).append("\n");
            }
            sb.append("\n");
        }

        // 添加文档上下文
        sb.append("【参考文档】\n").append(context).append("\n");

        // 添加当前问题
        sb.append("【当前问题】\n").append(query).append("\n\n");

        sb.append("请基于参考文档回答当前问题。如果历史对话中有相关信息，可以结合参考。");
        sb.append("\n要求：\n1. 直接回答问题\n2. 引用文档内容时标注来源，格式为【来源#编号: 文件名】\n3. 若无足够信息，请说明");

        return sb.toString();
    }

    private String generateReply(String query, String context, List<McpContext.ChatMessage> history) {
        String prompt = buildPromptWithHistory(query, context, history);
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
