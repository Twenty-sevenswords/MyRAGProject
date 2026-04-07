package com.yizhaoqi.smartpai.langgraph.core;

import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.node.NodeAction;
import com.yizhaoqi.smartpai.langgraph.node.StreamingNodeAction;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.function.Function;

/**
 * 状态图 - LangGraph核心组件
 * 定义节点、边和条件路由，执行状态机流程
 */
public class StateGraph {

    private static final Logger logger = LoggerFactory.getLogger(StateGraph.class);

    /** 节点映射 */
    private final Map<String, NodeAction> nodes = new LinkedHashMap<>();
    
    /** 入口节点 */
    private String entryPoint;
    
    /** 条件路由映射: 节点名 -> 路由函数 */
    private final Map<String, Function<AIState, String>> conditionalEdges = new HashMap<>();
    
    /** 固定边映射: 源节点 -> 目标节点 */
    private final Map<String, String> edges = new HashMap<>();
    
    /** 结束节点列表 */
    private final Set<String> endNodes = new HashSet<>();
    
    /** 图名称 */
    private String name;

    public StateGraph(String name) {
        this.name = name;
    }

    // ========== 构建方法 ==========

    public StateGraph addNode(String name, NodeAction action) {
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("节点已存在: " + name);
        }
        nodes.put(name, action);
        logger.debug("[StateGraph] 添加节点: {}", name);
        return this;
    }

    public StateGraph setEntryPoint(String nodeName) {
        if (!nodes.containsKey(nodeName)) {
            throw new IllegalArgumentException("入口节点不存在: " + nodeName);
        }
        this.entryPoint = nodeName;
        logger.debug("[StateGraph] 设置入口节点: {}", nodeName);
        return this;
    }

    public StateGraph addConditionalEdges(String fromNode, 
                                          Function<AIState, String> routingFunction,
                                          List<String> possibleNextNodes) {
        if (!nodes.containsKey(fromNode)) {
            throw new IllegalArgumentException("源节点不存在: " + fromNode);
        }
        conditionalEdges.put(fromNode, routingFunction);
        logger.debug("[StateGraph] 添加条件路由: {} -> {}", fromNode, possibleNextNodes);
        return this;
    }

    public StateGraph addEdge(String fromNode, String toNode) {
        if (!nodes.containsKey(fromNode)) {
            throw new IllegalArgumentException("源节点不存在: " + fromNode);
        }
        edges.put(fromNode, toNode);
        logger.debug("[StateGraph] 添加边: {} -> {}", fromNode, toNode);
        return this;
    }

    public StateGraph addEndNode(String nodeName) {
        endNodes.add(nodeName);
        logger.debug("[StateGraph] 添加结束节点: {}", nodeName);
        return this;
    }

    // ========== 执行方法 ==========

    public AIState invoke(AIState initialState) {
        logger.info("\n\n############################## StateGraph 执行开始 ##############################");
        logger.info("[StateGraph] 图名称: {}, 入口节点: {}", name, entryPoint);

        AIState state = initialState;
        String currentNode = entryPoint;

        while (currentNode != null && !state.isCompleted()) {
            logger.info("\n[StateGraph] ==================== 执行节点: {} ====================", currentNode);

            NodeAction action = nodes.get(currentNode);
            if (action == null) {
                logger.error("[StateGraph] 节点不存在: {}", currentNode);
                state.setErrorMessage("节点不存在: " + currentNode);
                break;
            }

            try {
                state = action.apply(state);
                state.recordNodeExecution(currentNode, "success", null);
                logger.info("[StateGraph] 节点 {} 执行完成", currentNode);

                if (endNodes.contains(currentNode)) {
                    logger.info("[StateGraph] 到达结束节点: {}", currentNode);
                    break;
                }

                currentNode = getNextNode(currentNode, state);

            } catch (Exception e) {
                logger.error("[StateGraph] 节点 {} 执行异常: {}", currentNode, e.getMessage(), e);
                state.setErrorMessage(e.getMessage());
                state.recordNodeExecution(currentNode, "failed", e.getMessage());
                break;
            }
        }

        state.setCompleted(true);
        logger.info("\n[StateGraph] ############################## 执行完成 ##############################\n");
        return state;
    }

    public Flux<GraphEvent> stream(AIState initialState) {
        logger.info("\n\n############################## StateGraph 流式执行开始 ##############################");
        logger.info("[StateGraph] 图名称: {}, 入口节点: {}", name, entryPoint);

        Sinks.Many<GraphEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        new Thread(() -> executeStream(initialState, sink)).start();

        return sink.asFlux();
    }

    private void executeStream(AIState state, Sinks.Many<GraphEvent> sink) {
        try {
            String currentNode = entryPoint;
            int maxIterations = 20;
            int iteration = 0;

            while (currentNode != null && !state.isCompleted() && iteration < maxIterations) {
                iteration++;
                logger.info("\n[StateGraph] ==================== 执行节点: {} ====================", currentNode);

                NodeAction action = nodes.get(currentNode);
                if (action == null) {
                    logger.error("[StateGraph] 节点不存在: {}", currentNode);
                    state.setErrorMessage("节点不存在: " + currentNode);
                    emit(sink, GraphEvent.error("节点不存在: " + currentNode, state.getSessionId()));
                    break;
                }

                try {
                    // 发送节点开始事件
                    emit(sink, GraphEvent.start(currentNode, getNodeStartMessage(currentNode), state.getSessionId()));

                    if (action instanceof StreamingNodeAction) {
                        StreamingNodeAction streamingAction = (StreamingNodeAction) action;
                        state = streamingAction.applyStreamWithEvents(state, event -> emit(sink, event))
                                .last(state)
                                .block();
                    } else {
                        state = action.apply(state);
                    }

                    state.recordNodeExecution(currentNode, "success", null);
                    logger.info("[StateGraph] 节点 {} 执行完成", currentNode);

                    // 发送节点完成事件
                    emit(sink, GraphEvent.complete(currentNode, getNodeCompleteMessage(currentNode, state), 
                            getNodeData(currentNode, state), state.getSessionId()));

                    if (endNodes.contains(currentNode)) {
                        logger.info("[StateGraph] 到达结束节点: {}", currentNode);
                        break;
                    }

                    currentNode = getNextNode(currentNode, state);

                } catch (Exception e) {
                    logger.error("[StateGraph] 节点 {} 执行异常: {}", currentNode, e.getMessage(), e);
                    state.setErrorMessage(e.getMessage());
                    state.recordNodeExecution(currentNode, "failed", e.getMessage());
                    emit(sink, GraphEvent.error(e.getMessage(), state.getSessionId()));
                    break;
                }
            }

            // 发送最终回复
            if (state.getFinalReply() != null) {
                emit(sink, GraphEvent.finalReply(state.getFinalReply(), state.getSources(), state.getSessionId()));
            }

            state.setCompleted(true);
            logger.info("\n[StateGraph] ############################## 流式执行完成 ##############################\n");

        } catch (Exception e) {
            logger.error("[StateGraph] 流式执行异常", e);
            emit(sink, GraphEvent.error(e.getMessage(), state.getSessionId()));
        } finally {
            sink.tryEmitComplete();
        }
    }

    private String getNextNode(String currentNode, AIState state) {
        if (conditionalEdges.containsKey(currentNode)) {
            String nextNode = conditionalEdges.get(currentNode).apply(state);
            logger.debug("[StateGraph] 条件路由: {} -> {}", currentNode, nextNode);
            return nextNode;
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
        switch (nodeName) {
            case "memory": return "检查历史记忆...";
            case "router": return "分析意图...";
            case "action": return "检索生成...";
            case "check": return "质检...";
            default: return "处理中...";
        }
    }

    private String getNodeCompleteMessage(String nodeName, AIState state) {
        switch (nodeName) {
            case "memory":
                return state.isCacheHit() ? "命中历史记忆" : "未命中历史记忆";
            case "router":
                return state.getIntent() != null ? 
                    String.format("意图: %s, 置信度: %.2f", 
                        state.getIntent().getIntent(), state.getIntent().getConfidence()) : "意图分析完成";
            case "action":
                return state.isUsedLLM() ? "LLM生成完成" : "快速检索完成";
            case "check":
                return state.isCheckPassed() ? "质检通过" : "质检未通过";
            default:
                return "处理完成";
        }
    }

    private Object getNodeData(String nodeName, AIState state) {
        switch (nodeName) {
            case "memory":
                return state.isCacheHit() ? Map.of("cached", true, "originalQuestion", state.getCachedQuestion()) : null;
            case "router":
                return state.getIntent();
            case "action":
                return Map.of("usedLLM", state.isUsedLLM(), "cost", state.getLlmCost());
            case "check":
                return Map.of("passed", state.isCheckPassed(), "score", state.getCheckScore());
            default:
                return null;
        }
    }

    public String getName() {
        return name;
    }

    public Set<String> getNodeNames() {
        return nodes.keySet();
    }
}
