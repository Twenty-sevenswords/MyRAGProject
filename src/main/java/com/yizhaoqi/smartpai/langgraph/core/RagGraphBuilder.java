package com.yizhaoqi.smartpai.langgraph.core;

import com.yizhaoqi.smartpai.langgraph.node.*;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

/**
 * RAG状态图构建器
 * 构建完整的RAG对话流程状态图
 * 
 * 图结构:
 *                    ┌──────────────────────────────────────┐
 *                    │                                      │
 *                    ▼                                      │
 *   START ──► memory ──► router ──► action ──► check ──► END
 *                │           │                          │
 *                │           │                          │
 *                ▼           └──────────────────────────┘
 *              (cache hit)              │
 *                  │                    │
 *                  ▼                    ▼
 *                 END               (retry)
 * 
 * 节点说明:
 * - memory:  检查历史问答缓存，命中则直接返回
 * - router:  意图识别，决定后续执行路径
 * - action:  RAG检索 + LLM生成
 * - check:   质量检查，不合格则降级
 */
@Component
public class RagGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(RagGraphBuilder.class);

    @Autowired
    private MemoryNode memoryNode;

    @Autowired
    private RouterNode routerNode;

    @Autowired
    private ActionNode actionNode;

    @Autowired
    private CheckNode checkNode;

    private StateGraph graph;

    /**
     * 构建状态图
     */
    @PostConstruct
    public void build() {
        logger.info("========== 构建RAG状态图 ==========");

        graph = new StateGraph("RAGFlowGraph");

        // 添加节点
        graph.addNode("memory", memoryNode);
        graph.addNode("router", routerNode);
        graph.addNode("action", actionNode);
        graph.addNode("check", checkNode);

        // 设置入口节点
        graph.setEntryPoint("memory");

        // 添加条件路由: memory节点
        graph.addConditionalEdges("memory", this::routeAfterMemory, 
                Arrays.asList("router", "END"));

        // 添加条件路由: router节点
        graph.addConditionalEdges("router", this::routeAfterRouter,
                Arrays.asList("action", "END"));

        // 固定边: action -> check
        graph.addEdge("action", "check");

        // 添加条件路由: check节点
        graph.addConditionalEdges("check", this::routeAfterCheck,
                Arrays.asList("END", "action"));

        // 标记结束节点
        graph.addEndNode("END");

        logger.info("========== RAG状态图构建完成 ==========");
        logger.info("图节点: {}", graph.getNodeNames());
    }

    /**
     * memory节点后的路由决策
     */
    private String routeAfterMemory(AIState state) {
        // 命中缓存，直接结束
        if (state.isCacheHit()) {
            logger.info("[GraphRouter] 命中缓存，跳转到END");
            return "END";
        }
        // 继续执行router
        return "router";
    }

    /**
     * router节点后的路由决策
     */
    private String routeAfterRouter(AIState state) {
        String decision = state.getRouteDecision();
        logger.info("[GraphRouter] router决策: {}", decision);
        
        switch (decision) {
            case "action":
                return "action";
            case "fallback":
                // 降级处理，目前仍然走action
                return "action";
            case "end":
            default:
                return "END";
        }
    }

    /**
     * check节点后的路由决策
     */
    private String routeAfterCheck(AIState state) {
        // 检查通过，结束
        if (state.isCheckPassed()) {
            logger.info("[GraphRouter] 检查通过，跳转到END");
            return "END";
        }

        // 检查未通过，判断是否需要重试
        if (state.isNeedsRetry() && state.getRetryCount() < 2) {
            state.setRetryCount(state.getRetryCount() + 1);
            state.setNeedsRetry(false);
            logger.info("[GraphRouter] 重试次数: {}, 返回action重新执行", state.getRetryCount());
            return "action";
        }

        // 不重试，直接结束（使用降级回复）
        logger.info("[GraphRouter] 检查未通过，跳转到END（降级回复）");
        return "END";
    }

    /**
     * 获取构建好的图
     */
    public StateGraph getGraph() {
        return graph;
    }
}
