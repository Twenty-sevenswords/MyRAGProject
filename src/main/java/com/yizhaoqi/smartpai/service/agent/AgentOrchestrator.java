package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.entity.AgentIntent;
import com.yizhaoqi.smartpai.entity.AgentResult;
import com.yizhaoqi.smartpai.entity.MemoryEntry;
import com.yizhaoqi.smartpai.entity.QaMemoryEntry;
import com.yizhaoqi.smartpai.repository.RedisRepository;
import com.yizhaoqi.smartpai.service.QaMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentOrchestrator {

    private final IntentAgent intentAgent;
    private final WorkAgent workAgent;
    private final CheckAgent checkAgent;

    @Autowired
    private RedisRepository redisRepository;

    @Autowired
    private QaMemoryService qaMemoryService;

    // 新增：构造函数注入
    public AgentOrchestrator(IntentAgent intentAgent,
                             WorkAgent workAgent,
                             CheckAgent checkAgent) {
        this.intentAgent = intentAgent;
        this.workAgent = workAgent;
        this.checkAgent = checkAgent;
    }

    // 会话状态管理
    private Map<String, SessionState> activeSessions = new ConcurrentHashMap<>();

    public Flux<AgentEvent> orchestrate(String message, String sessionId, String userId) {
        logger.info("\n\n############################## Agent Pipeline 启动 ##############################");
        logger.info("[Orchestrator] 会话ID: {}, 用户ID: {}", sessionId, userId);
        logger.info("[Orchestrator] 用户消息: {}", message);

        Sinks.Many<AgentEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        // 保存会话状态
        SessionState state = new SessionState(sessionId, userId, message);
        activeSessions.put(sessionId, state);

        // 异步执行
        new Thread(() -> runPipeline(message, sessionId, userId, sink, state)).start();

        return sink.asFlux();
    }

    private void runPipeline(String message, String sessionId, String userId,
                             Sinks.Many<AgentEvent> sink, SessionState state) {
        try {
            // Step 0: 检查历史记忆（如果用户问相同或类似问题）
            logger.info("\n[Orchestrator] ==================== Step 0: CheckMemory ====================");
            emit(sink, AgentEvent.start("MemoryAgent", "检查历史记忆...", sessionId));

            Optional<QaMemoryEntry> cachedAnswer = qaMemoryService.findSimilarAnswer(message, userId, null);

            if (cachedAnswer.isPresent()) {
                QaMemoryEntry memory = cachedAnswer.get();
                logger.info("[Orchestrator] >>> 命中历史记忆: 从缓存返回答案");
                emit(sink, AgentEvent.complete("MemoryAgent",
                        String.format("命中历史记忆（访问次数：%d）", memory.getAccessCount()),
                        Map.of("cached", true, "originalQuestion", memory.getQuestion()),
                        sessionId));

                // 直接返回缓存的答案
                emit(sink, AgentEvent.finalReply(memory.getAnswer(), memory.getSources(), sessionId));
                finish(sink, sessionId);
                logger.info("[Orchestrator] ############################## Pipeline 完成（命中缓存） ##############################\n");
                return;
            }

            emit(sink, AgentEvent.complete("MemoryAgent", "未命中历史记忆，继续正常流程", null, sessionId));
            logger.info("[Orchestrator] 未命中历史记忆，继续正常流程");

            // Step 1: IntentAgent
            logger.info("\n[Orchestrator] ==================== Step 1: IntentAgent ====================");
            emit(sink, AgentEvent.start("IntentAgent", "理解意图...", sessionId));

            AgentIntent intent = intentAgent.analyze(message, sessionId);
            saveMemory(sessionId, "intent", intent);
            state.setIntent(intent);

            emit(sink, AgentEvent.complete("IntentAgent",
                    String.format("意图：%s，置信度：%.2f", intent.getIntent(), intent.getConfidence()),
                    intent, sessionId));

            // 低置信度直接降级
            logger.info("[Orchestrator] 检查置信度: {} (阈值: 0.6)", intent.getConfidence());
            if (intent.getConfidence() < 0.6) {
                logger.warn("[Orchestrator] >>> 触发降级分支: 置信度过低");
                emit(sink, AgentEvent.fallback("系统", "意图不明确，使用关键词检索", sessionId));
                handleFallback(sink, sessionId, intent);
                finish(sink, sessionId);
                return;
            }
            logger.info("[Orchestrator] 置信度检查通过，继续正常流程");

            // Step 2: WorkAgent
            logger.info("\n[Orchestrator] ==================== Step 2: WorkAgent ====================");
            emit(sink, AgentEvent.start("WorkAgent", "检索生成...", sessionId));

            AgentResult work = workAgent.execute(intent, message, userId);
            saveMemory(sessionId, "work", work);
            state.setWorkResult(work);

            emit(sink, AgentEvent.complete("WorkAgent",
                    work.isUsedLLM() ? "LLM生成完成" : "快速检索完成",
                    Map.of("usedLLM", work.isUsedLLM(), "cost", work.getCost()),
                    sessionId));

            // Step 3: CheckAgent
            logger.info("\n[Orchestrator] ==================== Step 3: CheckAgent ====================");
            emit(sink, AgentEvent.start("CheckAgent", "质检...", sessionId));

            boolean passed = checkAgent.quickCheck(work, intent);
            logger.info("[Orchestrator] 快速检查结果: {}", passed ? "通过" : "不通过");

            if (!passed) {
                logger.info("[Orchestrator] 快速检查未通过，执行深度检查...");
                passed = checkAgent.deepCheck(work, message);
                logger.info("[Orchestrator] 深度检查结果: {}", passed ? "通过" : "不通过");
            }

            emit(sink, AgentEvent.complete("CheckAgent",
                    passed ? "质检通过" : "质检不通过，触发降级",
                    Map.of("passed", passed),
                    sessionId));

            // 最终输出
            logger.info("\n[Orchestrator] ==================== 最终输出 ====================");
            if (passed) {
                logger.info("[Orchestrator] >>> 质检通过分支: 输出正常回复");
                emit(sink, AgentEvent.finalReply(work.getReply(), work.getSources(), sessionId));
                
                // 保存问答记忆（质检通过才保存）
                saveQaMemory(message, userId, intent, work);
            } else {
                logger.warn("[Orchestrator] >>> 质检未通过分支: 触发降级策略");
                emit(sink, AgentEvent.fallback("系统", "回复未通过质检，使用降级策略", sessionId));
                handleFallback(sink, sessionId, work);
            }

            finish(sink, sessionId);
            logger.info("\n[Orchestrator] ############################## Pipeline 完成 ##############################\n");

        } catch (Exception e) {
            logger.error("[Orchestrator] Agent pipeline error", e);
            emit(sink, AgentEvent.error(e.getMessage(), sessionId));
            finish(sink, sessionId);
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    private void handleFallback(Sinks.Many<AgentEvent> sink, String sessionId, Object context) {
        logger.info("[Orchestrator] 执行降级处理...");
        // 降级处理：返回简单提示
        String fallbackReply = "系统暂时无法生成准确回复，请尝试更具体的问题描述。";
        emit(sink, AgentEvent.finalReply(fallbackReply, null, sessionId));
        logger.info("[Orchestrator] 降级处理完成");
    }

    private void finish(Sinks.Many<AgentEvent> sink, String sessionId) {
        sink.tryEmitComplete();
    }

    private void emit(Sinks.Many<AgentEvent> sink, AgentEvent event) {
        sink.tryEmitNext(event);
    }

    private void saveMemory(String sessionId, String key, Object value) {
        try {
            MemoryEntry entry = new MemoryEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setSessionId(sessionId);
            entry.setKey(key);
            entry.setValue(value);
            entry.setTimestamp(LocalDateTime.now());

            redisRepository.saveMemory(entry);
            logger.debug("[Orchestrator] 记忆保存成功: key={}, sessionId={}", key, sessionId);
        } catch (Exception e) {
            logger.error("[Orchestrator] 保存记忆失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存问答记忆到历史缓存
     */
    private void saveQaMemory(String question, String userId, AgentIntent intent, AgentResult result) {
        try {
            qaMemoryService.saveMemory(question, userId, intent, result);
            logger.info("[Orchestrator] 问答记忆保存成功");
        } catch (Exception e) {
            logger.error("[Orchestrator] 保存问答记忆失败: {}", e.getMessage(), e);
        }
    }

    // 内部状态类
    private static class SessionState {
        private String sessionId;
        private String userId;
        private String originalMessage;
        private AgentIntent intent;
        private AgentResult workResult;
        private long startTime;

        public SessionState(String sessionId, String userId, String message) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.originalMessage = message;
            this.startTime = System.currentTimeMillis();
        }

        public void setIntent(AgentIntent intent) { this.intent = intent; }
        public void setWorkResult(AgentResult workResult) { this.workResult = workResult; }
    }

    // 日志
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AgentOrchestrator.class);

    // AgentEvent 类定义...
    public static class AgentEvent {
        private String type;
        private String agent;
        private String message;
        private Object data;
        private String sessionId;
        private long timestamp;

        public static AgentEvent start(String agent, String message, String sessionId) {
            AgentEvent e = new AgentEvent();
            e.type = "start";
            e.agent = agent;
            e.message = message;
            e.sessionId = sessionId;
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static AgentEvent complete(String agent, String message, Object data, String sessionId) {
            AgentEvent e = new AgentEvent();
            e.type = "complete";
            e.agent = agent;
            e.message = message;
            e.data = data;
            e.sessionId = sessionId;
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static AgentEvent fallback(String agent, String message, String sessionId) {
            AgentEvent e = new AgentEvent();
            e.type = "fallback";
            e.agent = agent;
            e.message = message;
            e.sessionId = sessionId;
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static AgentEvent error(String message, String sessionId) {
            AgentEvent e = new AgentEvent();
            e.type = "error";
            e.agent = "system";
            e.message = message;
            e.sessionId = sessionId;
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        public static AgentEvent finalReply(String reply, Object sources, String sessionId) {
            AgentEvent e = new AgentEvent();
            e.type = "final";
            e.agent = "system";
            e.message = reply;
            e.data = sources;
            e.sessionId = sessionId;
            e.timestamp = System.currentTimeMillis();
            return e;
        }

        // getters
        public String getType() { return type; }
        public String getAgent() { return agent; }
        public String getMessage() { return message; }
        public Object getData() { return data; }
        public String getSessionId() { return sessionId; }
        public long getTimestamp() { return timestamp; }
    }
}