package com.yizhaoqi.smartpai.langgraph.service;

import com.yizhaoqi.smartpai.dto.AgentResult;
import com.yizhaoqi.smartpai.langgraph.core.RagGraphBuilder;
import com.yizhaoqi.smartpai.langgraph.core.StateGraph;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.metrics.GraphMetrics;
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

    @Autowired
    private GraphMetrics graphMetrics;

    public Flux<GraphEvent> chat(String message, String sessionId, String userId) {
        AIState initialState = new AIState(sessionId, userId, message);
        logger.info("LangGraph RAG start, sessionId={}, userId={}, traceId={}",
                sessionId, userId, initialState.getTraceId());

        graphMetrics.incrementGraphRuns("stream");
        StateGraph graph = graphBuilder.getGraph();
        return graph.stream(initialState)
                .doOnNext(event -> logger.debug("event={}, agent={}, traceId={}",
                        event.getType(), event.getAgent(), initialState.getTraceId()));
    }

    public AIState chatSync(String message, String sessionId, String userId) {
        AIState initialState = new AIState(sessionId, userId, message);
        logger.info("LangGraph sync start, sessionId={}, userId={}, traceId={}",
                sessionId, userId, initialState.getTraceId());

        graphMetrics.incrementGraphRuns("sync");

        StateGraph graph = graphBuilder.getGraph();
        AIState result = graph.invoke(initialState);

        if (result.getNodeTimings() != null) {
            result.getNodeTimings().forEach(graphMetrics::recordNodeDuration);
        }

        if (result.isCheckPassed() && result.getFinalReply() != null && !result.isCacheHit()) {
            try {
                AgentResult ar = AgentResult.builder()
                        .reply(result.getFinalReply())
                        .sources(result.getSources())
                        .usedLLM(result.isUsedLLM())
                        .cost(result.getLlmCost())
                        .build();
                qaMemoryService.saveMemory(result.getCurrentMessage(), result.getUserId(), result.getIntent(), ar);
            } catch (Exception e) {
                logger.error("Save QA memory failed", e);
            }
        }
        return result;
    }

    public String getGraphDiagram() {
        return "graph TB\n  START --> memory --> router --> action --> check --> END";
    }
}
