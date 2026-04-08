package com.yizhaoqi.smartpai.mcp.core;

import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.context.McpState;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import com.yizhaoqi.smartpai.mcp.function.FunctionRegistry;
import com.yizhaoqi.smartpai.mcp.memory.MemoryManager;
import com.yizhaoqi.smartpai.mcp.skill.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 引擎 - 控制平面核心
 * 负责：
 * 1. 任务调度
 * 2. 状态管理
 * 3. 记忆管理
 * 4. 技能协调
 */
@Component
public class McpEngine {

    private static final Logger logger = LoggerFactory.getLogger(McpEngine.class);

    @Autowired
    private FunctionRegistry functionRegistry;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired(required = false)
    private List<Skill> skills = new ArrayList<>();

    /** 活跃会话 */
    private final Map<String, McpContext> activeSessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册所有 Skill
        for (Skill skill : skills) {
            if (skill.isEnabled()) {
                functionRegistry.registerSkill(skill);
                logger.info("[MCP] 注册技能: {} - {}", skill.getName(), skill.getDescription());
            }
        }
        logger.info("[MCP] 控制平面初始化完成，已注册 {} 个技能", skills.size());
    }

    /**
     * 执行对话（流式）
     */
    public Flux<McpEvent> chat(String message, String sessionId, String userId) {
        logger.info("\n\n############################## MCP 控制平面启动 ##############################");
        logger.info("[MCP] 会话ID: {}, 用户ID: {}", sessionId, userId);
        logger.info("[MCP] 用户消息: {}", message);

        // 创建上下文
        McpContext context = new McpContext(sessionId, userId, message);
        activeSessions.put(sessionId, context);

        // 加载历史记忆
        if (context.isMemoryEnabled()) {
            memoryManager.loadMemory(context);
        }

        Sinks.Many<McpEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        
        // 异步执行
        new Thread(() -> executeMcp(context, sink)).start();

        return sink.asFlux()
                .doOnComplete(() -> activeSessions.remove(sessionId));
    }

    /**
     * 执行 MCP 流程
     */
    private void executeMcp(McpContext context, Sinks.Many<McpEvent> sink) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. 记忆检索阶段
            logger.info("[MCP] ========== 阶段1: 记忆检索 ==========");
            context.setState(McpState.MEMORY_RETRIEVAL);
            emit(sink, McpEvent.state("memory", "检索历史记忆...", context.getSessionId()));

            boolean memoryHit = checkMemoryHit(context);
            if (memoryHit) {
                logger.info("[MCP] ✅ 记忆命中，直接返回缓存答案");
                emit(sink, McpEvent.complete("memory", "命中历史记忆", context.getSessionId()));
                emitFinalReply(context, sink);
                return;
            }
            logger.info("[MCP] ❌ 未命中历史记忆，继续后续流程");
            emit(sink, McpEvent.complete("memory", "未命中历史记忆", context.getSessionId()));

            // 2. 意图识别阶段
            context.setState(McpState.INTENT_RECOGNITION);
            emit(sink, McpEvent.state("intent", "识别用户意图...", context.getSessionId()));
            recognizeIntent(context);
            emit(sink, McpEvent.complete("intent", 
                    String.format("意图: %s, 置信度: %.2f", context.getIntent(), context.getConfidence()),
                    context.getSessionId()));

            // 3. 技能选择和执行
            context.setState(McpState.SKILL_SELECTION);
            List<Skill> selectedSkills = selectSkills(context);
            logger.info("[MCP] 选择的技能: {}", selectedSkills.stream().map(Skill::getName).toList());

            // 4. 执行 RAG 检索
            context.setState(McpState.RAG_RETRIEVAL);
            executeSkillPipeline(context, selectedSkills, sink);

            // 5. 质量检查
            if (context.isCheckEnabled() && context.getFinalReply() != null) {
                context.setState(McpState.QUALITY_CHECK);
                emit(sink, McpEvent.state("check", "质量检查...", context.getSessionId()));
                boolean passed = executeCheck(context);
                context.setCheckPassed(passed);
                emit(sink, McpEvent.complete("check", 
                        passed ? "质检通过" : "质检未通过，将重试", context.getSessionId()));

                // 重试逻辑
                if (!passed && context.getRetryCount() < context.getMaxRetries()) {
                    context.setRetryCount(context.getRetryCount() + 1);
                    context.setState(McpState.RETRY);
                    emit(sink, McpEvent.state("retry", 
                            String.format("第 %d 次重试...", context.getRetryCount()), context.getSessionId()));
                    
                    // 重新执行技能
                    executeSkillPipeline(context, selectedSkills, sink);
                }
            }

            // 6. 发送最终回复
            emitFinalReply(context, sink);

            // 7. 保存记忆
            if (context.isMemoryEnabled()) {
                memoryManager.saveMemory(context);
            }

            context.setState(McpState.COMPLETED);
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("[MCP] ############################## MCP 执行完成 (耗时: {}ms) ##############################\n", elapsed);

        } catch (Exception e) {
            logger.error("[MCP] 执行异常", e);
            context.setState(McpState.ERROR);
            context.setErrorMessage(e.getMessage());
            emit(sink, McpEvent.error(e.getMessage(), context.getSessionId()));
        } finally {
            sink.tryEmitComplete();
        }
    }

    /**
     * 检查历史记忆命中
     */
    private boolean checkMemoryHit(McpContext context) {
        try {
            Object cached = memoryManager.getCachedAnswer(context.getUserMessage(), context.getUserId());
            if (cached != null) {
                context.setFinalReply(cached.toString());
                context.putSkillResult("cacheHit", true);
                return true;
            }
        } catch (Exception e) {
            logger.warn("[MCP] 记忆检索失败", e);
        }
        return false;
    }

    /**
     * 意图识别
     */
    private void recognizeIntent(McpContext context) {
        String message = context.getUserMessage().toLowerCase();
        
        // 简单规则匹配（可替换为 LLM）
        if (message.contains("搜索") || message.contains("查找") || message.contains("检索")) {
            context.setIntent("SEARCH");
        } else if (message.contains("比较") || message.contains("对比")) {
            context.setIntent("COMPARE");
        } else if (message.contains("总结") || message.contains("摘要")) {
            context.setIntent("SUMMARIZE");
        } else if (message.contains("问答") || message.contains("什么是")) {
            context.setIntent("QA");
        } else {
            context.setIntent("CHAT");
        }
        context.setConfidence(0.85);
    }

    /**
     * 技能选择
     */
    private List<Skill> selectSkills(McpContext context) {
        List<Skill> selected = new ArrayList<>();
        String intent = context.getIntent();

        for (Skill skill : skills) {
            if (!skill.isEnabled()) continue;
            
            boolean shouldInvoke = false;
            String name = skill.getName().toLowerCase();

            switch (intent) {
                case "SEARCH":
                    shouldInvoke = name.contains("rag") || name.contains("search");
                    break;
                case "COMPARE":
                case "SUMMARIZE":
                    shouldInvoke = name.contains("summary") || name.contains("rag");
                    break;
                case "QA":
                    shouldInvoke = name.contains("qa") || name.contains("rag");
                    break;
                default:
                    shouldInvoke = name.contains("rag");
            }

            if (shouldInvoke && skill.shouldInvoke(context)) {
                selected.add(skill);
            }
        }

        // 按优先级排序
        selected.sort(Comparator.comparingInt(Skill::getPriority));
        return selected;
    }

    /**
     * 执行技能管道
     */
    private void executeSkillPipeline(McpContext context, List<Skill> skills, Sinks.Many<McpEvent> sink) {
        for (Skill skill : skills) {
            try {
                context.addCalledSkill(skill.getName());
                emit(sink, McpEvent.state(skill.getName(), 
                        String.format("执行技能: %s", skill.getDescription()), context.getSessionId()));

                SkillParams params = buildSkillParams(context, skill);
                
                SkillResult result = null;
                if (skill.supportsStreaming() && context.isStreamingEnabled()) {
                    // 流式执行
                    List<SkillEvent> events = skill.executeStream(context, params)
                            .doOnNext(e -> {
                                if (e.getType() == SkillEvent.EventType.CHUNK) {
                                    emit(sink, McpEvent.chunk(skill.getName(), 
                                            String.valueOf(e.getData()), context.getSessionId()));
                                }
                            })
                            .collectList()
                            .block();
                    
                    // 从最后一个事件提取结果
                    if (events != null && !events.isEmpty()) {
                        SkillEvent lastEvent = events.get(events.size() - 1);
                        if (lastEvent.getData() instanceof SkillResult) {
                            result = (SkillResult) lastEvent.getData();
                        }
                    }
                } else {
                    // 同步执行
                    result = skill.execute(context, params);
                }

                if (result != null && result.isSuccess()) {
                    context.putSkillResult(skill.getName(), result.getData());
                    
                    // 处理 RAG 结果
                    if ("rag_search".equals(skill.getName()) || "rag".equals(skill.getName())) {
                        handleRagResult(context, result);
                    }
                }

                emit(sink, McpEvent.complete(skill.getName(), 
                        result != null ? result.getMessage() : "执行完成", context.getSessionId()));

            } catch (Exception e) {
                logger.error("[MCP] 技能执行失败: {}", skill.getName(), e);
                emit(sink, McpEvent.error(String.format("技能 %s 执行失败: %s", 
                        skill.getName(), e.getMessage()), context.getSessionId()));
            }
        }
        
        // 关键：RAG 无结果时自动 fallback 到联网搜索
        if (context.getFinalReply() == null && context.isWebSearchEnabled()) {
            logger.info("[MCP] RAG 无结果，自动 fallback 到联网搜索");
            executeFallbackWebSearch(context, sink);
        }
    }

    /**
     * Fallback 执行联网搜索
     */
    private void executeFallbackWebSearch(McpContext context, Sinks.Many<McpEvent> sink) {
        for (Skill skill : skills) {
            if ("web_search".equals(skill.getName()) && skill.isEnabled()) {
                try {
                    context.setNeedWebSearch(true);
                    emit(sink, McpEvent.state("web_search", "RAG 无结果，联网搜索中...", context.getSessionId()));
                    
                    SkillParams params = buildSkillParams(context, skill);
                    SkillResult result = skill.execute(context, params);
                    
                    if (result.isSuccess()) {
                        context.putSkillResult("web_search", result.getData());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) result.getData();
                        if (data != null && data.containsKey("reply")) {
                            context.setFinalReply((String) data.get("reply"));
                        }
                        emit(sink, McpEvent.complete("web_search", "联网搜索完成", context.getSessionId()));
                    }
                } catch (Exception e) {
                    logger.error("[MCP] 联网搜索失败", e);
                    emit(sink, McpEvent.error("联网搜索失败: " + e.getMessage(), context.getSessionId()));
                }
                break;
            }
        }
    }

    /**
     * 构建 Skill 参数
     */
    private SkillParams buildSkillParams(McpContext context, Skill skill) {
        SkillParams params = SkillParams.create()
                .put("message", context.getUserMessage())
                .put("userId", context.getUserId())
                .put("sessionId", context.getSessionId())
                .put("intent", context.getIntent());

        // 添加历史上下文
        if (context.isMemoryEnabled()) {
            params.put("history", context.getRecentHistory(5));
        }

        return params;
    }

    /**
     * 处理 RAG 结果
     */
    @SuppressWarnings("unchecked")
    private void handleRagResult(McpContext context, SkillResult result) {
        Object data = result.getData();
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            if (map.containsKey("reply")) {
                context.setFinalReply((String) map.get("reply"));
            }
            if (map.containsKey("sources")) {
                List<?> sources = (List<?>) map.get("sources");
                for (Object src : sources) {
                    if (src instanceof Map) {
                        Map<String, Object> s = (Map<String, Object>) src;
                        context.addSource(new McpContext.SourceInfo(
                                String.valueOf(s.get("id")),
                                String.valueOf(s.get("fileName")),
                                String.valueOf(s.get("content")),
                                s.get("score") instanceof Number ? ((Number) s.get("score")).doubleValue() : 0.0
                        ));
                    }
                }
            }
        }
    }

    /**
     * 执行质量检查
     */
    private boolean executeCheck(McpContext context) {
        for (Skill skill : skills) {
            if ("check".equalsIgnoreCase(skill.getName()) || "quality_check".equalsIgnoreCase(skill.getName())) {
                SkillParams params = SkillParams.create()
                        .put("question", context.getUserMessage())
                        .put("answer", context.getFinalReply());
                
                SkillResult result = skill.execute(context, params);
                if (result.isSuccess() && result.getData() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> checkResult = (Map<String, Object>) result.getData();
                    int score = checkResult.get("score") instanceof Number ? 
                            ((Number) checkResult.get("score")).intValue() : 0;
                    context.setCheckScore(score);
                    return score >= 6;
                }
            }
        }
        return true; // 没有检查技能时默认通过
    }

    /**
     * 发送最终回复
     */
    private void emitFinalReply(McpContext context, Sinks.Many<McpEvent> sink) {
        if (context.getFinalReply() != null) {
            emit(sink, McpEvent.finalReply(context.getFinalReply(), context.getSources(), context.getSessionId()));
        }
    }

    /**
     * 发送事件
     */
    private void emit(Sinks.Many<McpEvent> sink, McpEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * 获取活跃会话
     */
    public McpContext getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }
}
