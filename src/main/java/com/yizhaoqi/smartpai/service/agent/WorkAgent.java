package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作 Agent
 * 使用 LangChain4j 执行 RAG 流程
 */
@Component
public class WorkAgent {

    private static final Logger logger = LoggerFactory.getLogger(WorkAgent.class);

    private final HybridSearchService hybridSearchService;
    private final LangChain4jChatService chatService;
    private final AiProperties aiProperties;

    public WorkAgent(HybridSearchService hybridSearchService,
                     LangChain4jChatService chatService,
                     AiProperties aiProperties) {
        this.hybridSearchService = hybridSearchService;
        this.chatService = chatService;
        this.aiProperties = aiProperties;
    }

    public AgentResult execute(AgentIntent intent, String message, String userId) {
        logger.info("[WorkAgent] ========== 开始执行任务 ==========");
        logger.info("[WorkAgent] 意图类型: {}, 复杂度: {}, 需要历史: {}",
                intent.getIntent(), intent.getComplexity(), intent.isNeedsHistory());

        // 简单任务：直接检索返回（不走LLM，省成本）
        boolean isSimple = isSimpleSearch(intent);
        logger.info("[WorkAgent] 任务类型判断: {}", isSimple ? "简单任务(快速检索)" : "复杂任务(RAG+LLM)");

        if (isSimple) {
            logger.info("[WorkAgent] >>> 进入简单检索分支");
            AgentResult result = simpleSearch(intent, message, userId);
            logger.info("[WorkAgent] ========== 简单检索完成 ==========");
            return result;
        }

        // 复杂任务：RAG流程
        logger.info("[WorkAgent] >>> 进入RAG流程分支");
        AgentResult result = ragExecute(intent, message, userId);
        logger.info("[WorkAgent] ========== RAG流程完成 ==========");
        return result;
    }

    private boolean isSimpleSearch(AgentIntent intent) {
        boolean isSearch = "SEARCH".equals(intent.getIntent());
        boolean lowComplexity = intent.getComplexity() <= 2;
        boolean noHistory = !intent.isNeedsHistory();

        logger.debug("[WorkAgent] 简单任务条件检查: isSearch={}, lowComplexity={}, noHistory={}",
                isSearch, lowComplexity, noHistory);

        return isSearch && lowComplexity && noHistory;
    }

    private AgentResult simpleSearch(AgentIntent intent, String message, String userId) {
        logger.info("[WorkAgent] 执行简单检索，关键词: {}", intent.getKeywords());

        // 使用 searchWithPermission 进行带权限的检索
        List<SearchResult> results = hybridSearchService.searchWithPermission(
                String.join(" ", intent.getKeywords()),
                userId,
                5  // topK
        );

        logger.info("[WorkAgent] 检索结果数量: {} 条", results.size());
        if (!results.isEmpty()) {
            logger.debug("[WorkAgent] 检索结果预览: {}",
                    results.stream().limit(3).map(r -> r.getFileName()).collect(Collectors.toList()));
        }

        // 使用配置中的模板
        String simpleSearchTemplate = aiProperties.getAgent().getWork().getSimpleSearchTemplate();
        String reply = simpleSearchTemplate
                .replace("{count}", String.valueOf(results.size()))
                .replace("{results}", results.stream()
                        .map(r -> "• " + (r.getFileName() != null ? r.getFileName() : r.getFileMd5().substring(0, 8) + "...")
                                + " (片段#" + r.getChunkId() + ")")
                        .distinct()
                        .limit(5)
                        .collect(Collectors.joining("\n"))
                );

        logger.info("[WorkAgent] 简单检索完成，使用LLM: false, cost: 0");
        return AgentResult.builder()
                .reply(reply)
                .sources(results)
                .usedLLM(false)
                .cost(0)
                .build();
    }

    private AgentResult ragExecute(AgentIntent intent, String message, String userId) {
        logger.info("[WorkAgent] 执行RAG流程，原始消息: {}", message);

        // 带权限的混合检索
        List<SearchResult> results = hybridSearchService.searchWithPermission(
                message,
                userId,
                5
        );

        logger.info("[WorkAgent] RAG检索结果数量: {} 条", results.size());

        // 构建上下文
        String context = buildContext(results);
        logger.debug("[WorkAgent] 构建的上下文长度: {} 字符", context.length());

        // LLM生成 - 使用配置中的模板
        String template = aiProperties.getAgent().getWork().getTemplate();
        String prompt = template
                .replace("{context}", context)
                .replace("{message}", message);

        logger.info("[WorkAgent] 正在调用 LangChain4j 生成回复...");
        String reply = chatService.chat(prompt);
        logger.info("[WorkAgent] LLM 生成完成，回复长度: {} 字符", reply != null ? reply.length() : 0);

        logger.info("[WorkAgent] RAG流程完成，使用LLM: true, cost: 1");
        return AgentResult.builder()
                .reply(reply)
                .sources(results)
                .usedLLM(true)
                .cost(1)
                .build();
    }

    private String buildContext(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        String sourceFormat = aiProperties.getAgent().getWork().getSourceFormat();
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            SearchResult r = results.get(i);
            String fileName = r.getFileName() != null ? r.getFileName() : r.getFileMd5().substring(0, 8);
            sb.append(sourceFormat
                    .replace("{index}", String.valueOf(i + 1))
                    .replace("{fileName}", fileName)
                    .replace("{content}", r.getTextContent()));
            if (i < Math.min(5, results.size()) - 1) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }
}
