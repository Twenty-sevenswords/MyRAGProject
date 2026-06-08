package com.yizhaoqi.smartpai.langgraph.node;

import com.yizhaoqi.smartpai.config.properties.AiProperties;
import com.yizhaoqi.smartpai.dto.AgentIntent;
import com.yizhaoqi.smartpai.dto.RagAnswer;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.LangChain4jRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Action node for retrieval and generation.
 */
@Component
public class ActionNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(ActionNode.class);

    @Autowired
    private LangChain4jRagService ragService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private AiProperties aiProperties;

    @Override
    public String getName() {
        return "action";
    }

    @Override
    public AIState apply(AIState state) {
        AgentIntent intent = state.getIntent();
        String message = state.getCurrentMessage();
        String userId = state.getUserId();

        if (isSimpleSearch(intent)) {
            executeSimpleSearch(state, intent, userId);
        } else {
            executeRag(state, message, userId);
        }
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        return Flux.just(apply(state));
    }

    private boolean isSimpleSearch(AgentIntent intent) {
        if (intent == null) {
            return false;
        }
        boolean isSearch = "SEARCH".equals(intent.getIntent());
        boolean lowComplexity = intent.getComplexity() <= 2;
        boolean noHistory = !intent.isNeedsHistory();
        return isSearch && lowComplexity && noHistory;
    }

    private void executeSimpleSearch(AIState state, AgentIntent intent, String userId) {
        String query = intent != null && intent.getKeywords() != null && !intent.getKeywords().isEmpty()
                ? String.join(" ", intent.getKeywords())
                : state.getCurrentMessage();

        List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, 5);
        String simpleSearchTemplate = aiProperties.getAgent().getWork().getSimpleSearchTemplate();
        String reply = simpleSearchTemplate
                .replace("{count}", String.valueOf(results.size()))
                .replace("{results}", results.stream()
                        .map(r -> "- " + (r.getFileName() != null ? r.getFileName() : safeFilePrefix(r.getFileMd5()))
                                + " (chunk#" + r.getChunkId() + ")")
                        .distinct()
                        .limit(5)
                        .collect(Collectors.joining("\n")));

        state.setSearchResults(results);
        state.setGeneratedReply(reply);
        state.setSources(results);
        state.setUsedLLM(false);
        state.setLlmCost(0);
    }

    private void executeRag(AIState state, String message, String userId) {
        String searchQuery = state.getRewrittenQuery() != null && !state.getRewrittenQuery().isBlank()
                ? state.getRewrittenQuery()
                : message;

        RagAnswer ragAnswer = ragService.askWithPermission(searchQuery, userId, 8);
        state.setSearchResults(ragAnswer.getSearchResults());
        state.setGeneratedReply(ragAnswer.getReply());
        state.setSources(ragAnswer.getSearchResults());
        state.setUsedLLM(ragAnswer.isUsedLlm());
        state.setLlmCost(ragAnswer.isUsedLlm() ? 1 : 0);

        logger.info("[ActionNode] RAG reply length: {}",
                ragAnswer.getReply() != null ? ragAnswer.getReply().length() : 0);
    }

    private String safeFilePrefix(String fileMd5) {
        if (fileMd5 == null || fileMd5.isBlank()) {
            return "unknown";
        }
        return fileMd5.length() > 8 ? fileMd5.substring(0, 8) : fileMd5;
    }
}
