package com.yizhaoqi.smartpai.langgraph.service;

import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.langgraph.core.RagGraphBuilder;
import com.yizhaoqi.smartpai.langgraph.core.StateGraph;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.QaMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class LangGraphRagService {
    private static final Logger logger = LoggerFactory.getLogger(LangGraphRagService.class);

    @Autowired
    private RagGraphBuilder graphBuilder;

    @Autowired
    private QaMemoryService qaMemoryService;

    public Flux<GraphEvent> chat(String message, String sessionId, String userId) {
        logger.info("LangGraph RAG 启动, sessionId={}, userId={}", sessionId, userId);
        AIState initialState = new AIState(sessionId, userId, message);
        StateGraph graph = graphBuilder.getGraph();
        return graph.stream(initialState)
                .doOnNext(event -> logger.debug("事件: type={}, agent={}", event.getType(), event.getAgent()));
    }

    public AIState chatSync(String message, String sessionId, String userId) {
        AIState initialState = new AIState(sessionId, userId, message);
        StateGraph graph = graphBuilder.getGraph();
        AIState result = graph.invoke(initialState);
        if (result.isCheckPassed() && result.getFinalReply() != null && !result.isCacheHit()) {
            try {
                AgentResult ar = AgentResult.builder()
                        .reply(result.getFinalReply())
                        .sources(result.getSources())
                        .usedLLM(result.isUsedLLM())
                        .cost(result.getLlmCost()).build();
                qaMemoryService.saveMemory(result.getCurrentMessage(), result.getUserId(), result.getIntent(), ar);
            } catch (Exception e) {
                logger.error("保存问答记忆失败", e);
            }
        }
        return result;
    }

    public String getGraphDiagram() {
        return "graph TB\n  START --> memory --> router --> action --> check --> END";
    }
}
