package com.yizhaoqi.smartpai.langgraph.node;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 动作节点 - 工作Agent
 * 执行RAG检索、上下文读取、大模型生成
 */
@Component
public class ActionNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ActionNode.class);

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private LangChain4jChatService chatService;

    @Autowired
    private AiProperties aiProperties;

    @Override
    public String getName() {
        return "action";
    }

    @Override
    public AIState apply(AIState state) {
        logger.info("[ActionNode] ========== 开始执行任务 ==========");
        
        AgentIntent intent = state.getIntent();
        String message = state.getCurrentMessage();
        String userId = state.getUserId();

        logger.info("[ActionNode] 意图类型: {}, 复杂度: {}, 需要历史: {}",
                intent != null ? intent.getIntent() : "UNKNOWN",
                intent != null ? intent.getComplexity() : 1,
                intent != null ? intent.isNeedsHistory() : false);

        // 判断任务类型
        boolean isSimple = isSimpleSearch(intent);
        logger.info("[ActionNode] 任务类型判断: {}", isSimple ? "简单任务(快速检索)" : "复杂任务(RAG+LLM)");

        if (isSimple) {
            logger.info("[ActionNode] >>> 进入简单检索分支");
            executeSimpleSearch(state, intent, message, userId);
        } else {
            logger.info("[ActionNode] >>> 进入RAG流程分支");
            executeRAG(state, intent, message, userId);
        }

        logger.info("[ActionNode] ========== 任务执行完成 ==========");
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    /**
     * 判断是否为简单检索任务
     */
    private boolean isSimpleSearch(AgentIntent intent) {
        if (intent == null) {
            return false;
        }
        boolean isSearch = "SEARCH".equals(intent.getIntent());
        boolean lowComplexity = intent.getComplexity() <= 2;
        boolean noHistory = !intent.isNeedsHistory();

        logger.debug("[ActionNode] 简单任务条件检查: isSearch={}, lowComplexity={}, noHistory={}",
                isSearch, lowComplexity, noHistory);

        return isSearch && lowComplexity && noHistory;
    }

    /**
     * 执行简单检索
     */
    private void executeSimpleSearch(AIState state, AgentIntent intent, String message, String userId) {
        logger.info("[ActionNode] 执行简单检索，关键词: {}", intent.getKeywords());

        // 使用 searchWithPermission 进行带权限的检索
        List<SearchResult> results = hybridSearchService.searchWithPermission(
                String.join(" ", intent.getKeywords()),
                userId,
                5  // topK
        );

        logger.info("[ActionNode] 检索结果数量: {} 条", results.size());
        if (!results.isEmpty()) {
            logger.debug("[ActionNode] 检索结果预览: {}",
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

        // 更新状态
        state.setSearchResults(results);
        state.setGeneratedReply(reply);
        state.setSources(results);
        state.setUsedLLM(false);
        state.setLlmCost(0);

        logger.info("[ActionNode] 简单检索完成，使用LLM: false, cost: 0");
    }

    /**
     * 执行RAG流程
     */
    private void executeRAG(AIState state, AgentIntent intent, String message, String userId) {
        logger.info("[ActionNode] 执行RAG流程，原始消息: {}", message);

        // 带权限的混合检索
        List<SearchResult> results = hybridSearchService.searchWithPermission(
                message,
                userId,
                5
        );

        logger.info("[ActionNode] RAG检索结果数量: {} 条", results.size());

        // 构建上下文
        String context = buildContext(results);
        logger.debug("[ActionNode] 构建的上下文长度: {} 字符", context.length());

        // LLM生成 - 使用配置中的模板
        String template = aiProperties.getAgent().getWork().getTemplate();
        String prompt = template
                .replace("{context}", context)
                .replace("{message}", message);

        logger.info("[ActionNode] 正在调用 LLM 生成回复...");
        String reply = chatService.chat(prompt);
        logger.info("[ActionNode] LLM 生成完成，回复长度: {} 字符", reply != null ? reply.length() : 0);

        // 更新状态
        state.setSearchResults(results);
        state.setGeneratedReply(reply);
        state.setSources(results);
        state.setUsedLLM(true);
        state.setLlmCost(1);

        logger.info("[ActionNode] RAG流程完成，使用LLM: true, cost: 1");
    }

    /**
     * 构建上下文
     */
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
