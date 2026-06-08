package com.yizhaoqi.smartpai.langgraph.core;

import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.node.NodeAction;
import com.yizhaoqi.smartpai.langgraph.node.StreamingNodeAction;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Lightweight state graph runtime for LangGraph-style pipelines.
 */
public class StateGraph {

    private static final Logger logger = LoggerFactory.getLogger(StateGraph.class);

    private final Map<String, NodeAction> nodes = new LinkedHashMap<>();
    private final Map<String, Function<AIState, String>> conditionalEdges = new HashMap<>();
    private final Map<String, String> edges = new HashMap<>();
    private final Set<String> endNodes = new HashSet<>();

    private String entryPoint;
    private String name;

    public StateGraph(String name) {
        this.name = name;
    }

    // ---------- Build ----------

    public StateGraph addNode(String name, NodeAction action) {
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("Node already exists: " + name);
        }
        nodes.put(name, action);
        return this;
    }

    public StateGraph setEntryPoint(String nodeName) {
        if (!nodes.containsKey(nodeName)) {
            throw new IllegalArgumentException("Entry node not found: " + nodeName);
        }
        this.entryPoint = nodeName;
        return this;
    }

    public StateGraph addConditionalEdges(String fromNode,
                                          Function<AIState, String> routingFunction,
                                          List<String> possibleNextNodes) {
        if (!nodes.containsKey(fromNode)) {
            throw new IllegalArgumentException("Source node not found: " + fromNode);
        }
        conditionalEdges.put(fromNode, routingFunction);
        return this;
    }

    public StateGraph addEdge(String fromNode, String toNode) {
        if (!nodes.containsKey(fromNode)) {
            throw new IllegalArgumentException("Source node not found: " + fromNode);
        }
        edges.put(fromNode, toNode);
        return this;
    }

    public StateGraph addEndNode(String nodeName) {
        endNodes.add(nodeName);
        return this;
    }

    // ---------- Execute (sync) ----------

    public AIState invoke(AIState initialState) {
        AIState state = initialState;
        String currentNode = entryPoint;

        while (currentNode != null && !state.isCompleted()) {
            NodeAction action = nodes.get(currentNode);
            if (action == null) {
                state.setErrorMessage("Node not found: " + currentNode);
                break;
            }

            long startedAt = System.currentTimeMillis();
            try {
                state = action.apply(state);
                long durationMs = System.currentTimeMillis() - startedAt;

                state.recordNodeExecution(currentNode, "success", null);
                state.recordNodeTiming(currentNode, durationMs);

                if (endNodes.contains(currentNode)) {
                    break;
                }
                currentNode = getNextNode(currentNode, state);
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startedAt;
                logger.error("Node execution failed: {}", currentNode, e);
                state.setErrorMessage(e.getMessage());
                state.recordNodeExecution(currentNode, "failed", e.getMessage());
                state.recordNodeTiming(currentNode, durationMs);
                break;
            }
        }

        state.setCompleted(true);
        return state;
    }

    // ---------- Execute (stream) ----------

    public Flux<GraphEvent> stream(AIState initialState) {
        Sinks.Many<GraphEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        new Thread(() -> executeStream(initialState, sink), "state-graph-stream").start();
        return sink.asFlux();
    }

    private void executeStream(AIState state, Sinks.Many<GraphEvent> sink) {
        try {
            String currentNode = entryPoint;
            int maxIterations = 20;
            int iteration = 0;

            while (currentNode != null && !state.isCompleted() && iteration < maxIterations) {
                iteration++;

                NodeAction action = nodes.get(currentNode);
                if (action == null) {
                    state.setErrorMessage("Node not found: " + currentNode);
                    emit(sink, GraphEvent.error(state.getErrorMessage(), state.getSessionId()));
                    break;
                }

                long startedAt = System.currentTimeMillis();
                try {
                    emit(sink, GraphEvent.start(currentNode, getNodeStartMessage(currentNode), state.getSessionId()));

                    if (action instanceof StreamingNodeAction streamingAction) {
                        state = streamingAction.applyStreamWithEvents(state, event -> emit(sink, event))
                                .last(state)
                                .block();
                    } else {
                        state = action.apply(state);
                    }

                    long durationMs = System.currentTimeMillis() - startedAt;
                    state.recordNodeExecution(currentNode, "success", null);
                    state.recordNodeTiming(currentNode, durationMs);

                    emit(sink, GraphEvent.complete(
                            currentNode,
                            getNodeCompleteMessage(currentNode, state),
                            getNodeData(currentNode, state),
                            state.getSessionId()));

                    if (endNodes.contains(currentNode)) {
                        break;
                    }

                    currentNode = getNextNode(currentNode, state);
                } catch (Exception e) {
                    long durationMs = System.currentTimeMillis() - startedAt;
                    logger.error("Node execution failed: {}", currentNode, e);
                    state.setErrorMessage(e.getMessage());
                    state.recordNodeExecution(currentNode, "failed", e.getMessage());
                    state.recordNodeTiming(currentNode, durationMs);
                    emit(sink, GraphEvent.error(e.getMessage(), state.getSessionId()));
                    break;
                }
            }

            if (state.getFinalReply() != null) {
                emit(sink, GraphEvent.finalReply(state.getFinalReply(), state.getSources(), state.getSessionId()));
            }

            state.setCompleted(true);
        } catch (Exception e) {
            logger.error("Stream execution failed", e);
            emit(sink, GraphEvent.error(e.getMessage(), state.getSessionId()));
        } finally {
            sink.tryEmitComplete();
        }
    }

    // ---------- Helpers ----------

    private String getNextNode(String currentNode, AIState state) {
        if (conditionalEdges.containsKey(currentNode)) {
            return conditionalEdges.get(currentNode).apply(state);
        }
        if (edges.containsKey(currentNode)) {
            return edges.get(currentNode);
        }
        return null;
    }

    private void emit(Sinks.Many<GraphEvent> sink, GraphEvent event) {
        sink.tryEmitNext(event);
    }

    private String getNodeStartMessage(String nodeName) {
        return switch (nodeName) {
            case "memory" -> "检查历史记忆...";
            case "router" -> "分析意图...";
            case "action" -> "检索与生成中...";
            case "check" -> "质量检查中...";
            default -> "处理中...";
        };
    }

    private String getNodeCompleteMessage(String nodeName, AIState state) {
        return switch (nodeName) {
            case "memory" -> state.isCacheHit() ? "命中历史记忆" : "未命中历史记忆";
            case "router" -> state.getIntent() != null
                    ? String.format("意图: %s, 置信度: %.2f", state.getIntent().getIntent(), state.getIntent().getConfidence())
                    : "意图分析完成";
            case "action" -> state.isUsedLLM() ? "LLM 生成完成" : "快速检索完成";
            case "check" -> state.isCheckPassed() ? "质检通过" : "质检未通过";
            default -> "处理完成";
        };
    }

    private Object getNodeData(String nodeName, AIState state) {
        return switch (nodeName) {
            case "memory" -> state.isCacheHit()
                    ? Map.of("cached", true, "originalQuestion", state.getCachedQuestion())
                    : null;
            case "router" -> state.getIntent();
            case "action" -> Map.of("usedLLM", state.isUsedLLM(), "cost", state.getLlmCost());
            case "check" -> Map.of("passed", state.isCheckPassed(), "score", state.getCheckScore());
            default -> null;
        };
    }

    public String getName() {
        return name;
    }

    public Set<String> getNodeNames() {
        return nodes.keySet();
    }
}
