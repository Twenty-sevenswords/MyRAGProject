package com.yizhaoqi.smartpai.langgraph.node;

import com.yizhaoqi.smartpai.entity.QaMemoryEntry;
import com.yizhaoqi.smartpai.langgraph.event.GraphEvent;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.QaMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 记忆节点 - 检查历史问答缓存
 * 如果命中缓存，直接返回答案，跳过后续节点
 */
@Component
public class MemoryNode implements StreamingNodeAction {

    private static final Logger logger = LoggerFactory.getLogger(MemoryNode.class);

    @Autowired
    private QaMemoryService qaMemoryService;

    @Override
    public String getName() {
        return "memory";
    }

    @Override
    public AIState apply(AIState state) {
        logger.info("[MemoryNode] ========== 开始检查历史记忆 ==========");
        logger.info("[MemoryNode] 会话ID: {}, 用户消息: {}", state.getSessionId(), state.getCurrentMessage());

        try {
            // 查找相似问答
            Optional<QaMemoryEntry> cachedAnswer = qaMemoryService.findSimilarAnswer(
                    state.getCurrentMessage(), 
                    state.getUserId(), 
                    null
            );

            if (cachedAnswer.isPresent()) {
                QaMemoryEntry memory = cachedAnswer.get();
                logger.info("[MemoryNode] >>> 命中历史记忆: 从缓存返回答案");

                // 更新状态
                state.setCacheHit(true);
                state.setCachedQuestion(memory.getQuestion());
                state.setFinalReply(memory.getAnswer());
                state.setSources(memory.getSources());
                state.setCompleted(true);  // 标记完成，跳过后续节点

                logger.info("[MemoryNode] ========== 命中缓存，跳过后续节点 ==========");
                return state;
            }

            logger.info("[MemoryNode] 未命中历史记忆，继续正常流程");
            state.setCacheHit(false);

        } catch (Exception e) {
            logger.error("[MemoryNode] 检查历史记忆异常: {}", e.getMessage(), e);
            state.setCacheHit(false);
        }

        logger.info("[MemoryNode] ========== 记忆检查完成 ==========");
        return state;
    }

    @Override
    public Flux<AIState> applyStreamWithEvents(AIState state, Consumer<GraphEvent> eventSink) {
        logger.info("[MemoryNode] ========== 开始检查历史记忆 ==========");

        try {
            // 查找相似问答
            Optional<QaMemoryEntry> cachedAnswer = qaMemoryService.findSimilarAnswer(
                    state.getCurrentMessage(), 
                    state.getUserId(), 
                    null
            );

            if (cachedAnswer.isPresent()) {
                QaMemoryEntry memory = cachedAnswer.get();
                logger.info("[MemoryNode] >>> 命中历史记忆: 从缓存返回答案");

                // 更新状态
                state.setCacheHit(true);
                state.setCachedQuestion(memory.getQuestion());
                state.setFinalReply(memory.getAnswer());
                state.setSources(memory.getSources());
                state.setCompleted(true);

                logger.info("[MemoryNode] ========== 命中缓存，跳过后续节点 ==========");
                return Flux.just(state);
            }

            logger.info("[MemoryNode] 未命中历史记忆，继续正常流程");
            state.setCacheHit(false);

        } catch (Exception e) {
            logger.error("[MemoryNode] 检查历史记忆异常: {}", e.getMessage(), e);
            state.setCacheHit(false);
        }

        return Flux.just(state);
    }
}
