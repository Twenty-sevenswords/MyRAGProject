package com.yizhaoqi.smartpai.langgraph.node;

import com.yizhaoqi.smartpai.langgraph.state.AIState;
import reactor.core.publisher.Flux;

/**
 * 节点动作接口 - LangGraph节点执行单元
 * 每个节点实现此接口，负责处理状态并返回更新
 */
public interface NodeAction {

    /**
     * 获取节点名称
     */
    String getName();

    /**
     * 执行节点逻辑（同步版本）
     * @param state 当前状态
     * @return 更新后的状态
     */
    AIState apply(AIState state);

    /**
     * 执行节点逻辑（流式版本）
     * 支持实时返回执行进度和中间结果
     * @param state 当前状态
     * @return 状态更新流
     */
    default Flux<AIState> applyStream(AIState state) {
        return Flux.just(apply(state));
    }
}
