package com.yizhaoqi.smartpai.langgraph.node;

import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import reactor.core.publisher.Flux;

/**
 * 流式节点动作接口 - 支持实时事件推送
 * 用于需要逐步推送中间结果的节点（如LLM流式生成）
 */
public interface StreamingNodeAction extends NodeAction {

    /**
     * 执行节点逻辑（流式版本）
     * 支持实时返回执行进度、中间结果和事件
     * @param state 当前状态
     * @param eventSink 事件接收器
     * @return 状态更新流
     */
    Flux<AIState> applyStreamWithEvents(AIState state, java.util.function.Consumer<GraphEvent> eventSink);

    @Override
    default Flux<AIState> applyStream(AIState state) {
        return applyStreamWithEvents(state, event -> {});
    }
}
