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
 * Agentic RAG 状态图构建器
 *
 * 完整流水线（7节点）：
 *
 *   START
 *     │
 *     ▼
 *   memory ──(命中缓存)──► END
 *     │
 *     ▼
 *   queryAnalysis          ← 查询改写 + 意图判断
 *     │
 *     ▼
 *   router                 ← 意图分类 + 路由决策
 *     │
 *     ▼
 *   action                 ← 混合检索（KNN + BM25）
 *     │
 *     ▼
 *   grading                ← 文档相关性评分过滤（CRAG思路）
 *     │(sufficientContext=false)
 *     ├──► action（重新检索，使用改写查询）
 *     │
 *     ▼
 *   hallucinationCheck     ← 幻觉检测（Self-RAG思路）
 *     │(failed, retryCount<2)
 *     ├──► action（重试生成）
 *     │
 *     ▼
 *   check                  ← 规则质检（长度/关键词/来源标注）
 *     │
 *     ▼
 *   END
 */
@Component
public class RagGraphBuilder {

    private static final Logger logger = LoggerFactory.getLogger(RagGraphBuilder.class);

    @Autowired private MemoryNode memoryNode;
    @Autowired private QueryAnalysisNode queryAnalysisNode;
    @Autowired private RouterNode routerNode;
    @Autowired private ActionNode actionNode;
    @Autowired private GradingNode gradingNode;
    @Autowired private HallucinationCheckNode hallucinationCheckNode;
    @Autowired private CheckNode checkNode;

    private StateGraph graph;

    @PostConstruct
    public void build() {
        logger.info("========== 构建 Agentic RAG 状态图 ==========");

        graph = new StateGraph("AgenticRAGGraph");

        // 注册节点
        graph.addNode("memory",             memoryNode);
        graph.addNode("queryAnalysis",      queryAnalysisNode);
        graph.addNode("router",             routerNode);
        graph.addNode("action",             actionNode);
        graph.addNode("grading",            gradingNode);
        graph.addNode("hallucinationCheck", hallucinationCheckNode);
        graph.addNode("check",              checkNode);

        // 入口
        graph.setEntryPoint("memory");

        // memory → queryAnalysis | END（缓存命中）
        graph.addConditionalEdges("memory", this::routeAfterMemory,
                Arrays.asList("queryAnalysis", "END"));

        // queryAnalysis → router（固定）
        graph.addEdge("queryAnalysis", "router");

        // router → action | END
        graph.addConditionalEdges("router", this::routeAfterRouter,
                Arrays.asList("action", "END"));

        // action → grading（固定）
        graph.addEdge("action", "grading");

        // grading → hallucinationCheck | action（文档不足时重检索）
        graph.addConditionalEdges("grading", this::routeAfterGrading,
                Arrays.asList("hallucinationCheck", "action"));

        // hallucinationCheck → check | action（幻觉时重生成）
        graph.addConditionalEdges("hallucinationCheck", this::routeAfterHallucinationCheck,
                Arrays.asList("check", "action"));

        // check → END | action（规则不通过时重试）
        graph.addConditionalEdges("check", this::routeAfterCheck,
                Arrays.asList("END", "action"));

        graph.addEndNode("END");

        logger.info("========== Agentic RAG 状态图构建完成，节点: {} ==========", graph.getNodeNames());
    }

    // ── 路由函数 ──────────────────────────────────────────────────────────────

    private String routeAfterMemory(AIState state) {
        if (state.isCacheHit()) {
            logger.info("[Router] memory → END（缓存命中）");
            return "END";
        }
        return "queryAnalysis";
    }

    private String routeAfterRouter(AIState state) {
        String decision = state.getRouteDecision();
        logger.info("[Router] router决策: {}", decision);
        return "end".equals(decision) ? "END" : "action";
    }

    private String routeAfterGrading(AIState state) {
        if (!state.isSufficientContext() && state.getRetryCount() < 1) {
            state.setRetryCount(state.getRetryCount() + 1);
            logger.info("[Router] grading → action（文档不足，触发重检索，第{}次）", state.getRetryCount());
            return "action";
        }
        return "hallucinationCheck";
    }

    private String routeAfterHallucinationCheck(AIState state) {
        if (!state.isHallucinationPassed() && state.getRetryCount() < 2) {
            state.setRetryCount(state.getRetryCount() + 1);
            logger.warn("[Router] hallucinationCheck → action（检测到幻觉，重新生成，第{}次）", state.getRetryCount());
            return "action";
        }
        return "check";
    }

    private String routeAfterCheck(AIState state) {
        if (state.isCheckPassed()) {
            logger.info("[Router] check → END（质检通过）");
            return "END";
        }
        if (state.isNeedsRetry() && state.getRetryCount() < 2) {
            state.setRetryCount(state.getRetryCount() + 1);
            state.setNeedsRetry(false);
            logger.info("[Router] check → action（质检未通过，重试第{}次）", state.getRetryCount());
            return "action";
        }
        logger.warn("[Router] check → END（质检未通过，降级输出）");
        return "END";
    }

    public StateGraph getGraph() {
        return graph;
    }
}
