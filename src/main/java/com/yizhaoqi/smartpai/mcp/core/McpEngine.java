package com.yizhaoqi.smartpai.mcp.core;

import com.yizhaoqi.smartpai.entity.QaMemoryEntry;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.context.McpState;
import com.yizhaoqi.smartpai.mcp.event.McpEvent;
import com.yizhaoqi.smartpai.mcp.function.FunctionRegistry;
import com.yizhaoqi.smartpai.mcp.memory.MemoryManager;
import com.yizhaoqi.smartpai.mcp.router.PreRouter;
import com.yizhaoqi.smartpai.mcp.router.RouteResult;
import com.yizhaoqi.smartpai.mcp.skill.*;
import com.yizhaoqi.smartpai.service.QaMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * MCP 引擎 - 控制平面核心
 * 负责：
 * 1. 任务调度
 * 2. 状态管理
 * 3. 记忆管理
 * 4. 技能协调
 * 5. 前置路由（可插拔）
 */
@Component
public class McpEngine {

    private static final Logger logger = LoggerFactory.getLogger(McpEngine.class);
    
    /** 默认超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    @Autowired
    private FunctionRegistry functionRegistry;

    @Autowired
    private MemoryManager memoryManager;
    
    @Autowired
    private QaMemoryService qaMemoryService;
    
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("mcpTaskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @Autowired(required = false)
    private List<Skill> skills = new ArrayList<>();

    /** 前置路由器列表（可插拔） */
    @Autowired(required = false)
    private List<PreRouter> preRouters = new ArrayList<>();

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
        
        // 排序前置路由器（按优先级）
        preRouters.sort(Comparator.comparingInt(PreRouter::getOrder));
        
        logger.info("[MCP] 控制平面初始化完成，已注册 {} 个技能，{} 个前置路由器",
                skills.size(), preRouters.size());
        for (PreRouter router : preRouters) {
            logger.info("[MCP]   - 路由器: {} (优先级: {})", router.getName(), router.getOrder());
        }
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
        
        // 使用线程池异步执行（替代 new Thread）
        taskExecutor.execute(() -> executeMcp(context, sink));

        return sink.asFlux()
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .onErrorResume(TimeoutException.class, e -> {
                    logger.error("[MCP] 处理超时，会话ID: {}", sessionId);
                    emit(sink, McpEvent.error("处理超时，请稍后重试", sessionId));
                    sink.tryEmitComplete();
                    return Flux.empty();
                })
                .doOnComplete(() -> activeSessions.remove(sessionId));
    }

    /**
     * 执行 MCP 流程
     */
    private void executeMcp(McpContext context, Sinks.Many<McpEvent> sink) {
        long startTime = System.currentTimeMillis();
        try {
            // 0. 前置路由阶段（可插拔）
            logger.info("[MCP] ========== 阶段0: 前置路由 ==========");
            emit(sink, McpEvent.state("router", "检查前置路由...", context.getSessionId()));
            
            RouteResult routeResult = checkPreRouters(context);
            if (routeResult != null && routeResult.getType() != RouteResult.RouteType.CONTINUE) {
                logger.info("[MCP] ✅ 前置路由命中: {}, 原因: {}", routeResult.getType(), routeResult.getReason());
                emit(sink, McpEvent.complete("router",
                        String.format("路由命中: %s", routeResult.getReason()),
                        context.getSessionId()));
                
                // 处理路由结果
                if (handleRouteResult(context, routeResult, sink)) {
                    return; // 已处理完成，直接返回
                }
            } else {
                logger.info("[MCP] ❌ 未命中前置路由，继续正常流程");
                emit(sink, McpEvent.complete("router", "未命中前置路由", context.getSessionId()));
            }

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
            logger.info("[MCP] ========== 阶段2: 意图识别 ==========");
            context.setState(McpState.INTENT_RECOGNITION);
            emit(sink, McpEvent.state("intent", "识别用户意图...", context.getSessionId()));
            recognizeIntent(context);
            logger.info("[MCP] 意图识别结果: {}, 置信度: {}", context.getIntent(), context.getConfidence());
            emit(sink, McpEvent.complete("intent",
                    String.format("意图: %s, 置信度: %.2f", context.getIntent(), context.getConfidence()),
                    context.getSessionId()));

            // 3. 技能选择和执行
            logger.info("[MCP] ========== 阶段3: 技能选择 ==========");
            context.setState(McpState.SKILL_SELECTION);
            List<Skill> selectedSkills = selectSkills(context);
            logger.info("[MCP] 选择的技能数量: {}, 技能列表: {}",selectedSkills.size(), selectedSkills.stream().map(Skill::getName).toList());
            if (selectedSkills.isEmpty()) {
                logger.warn("[MCP] ⚠️  没有选择到任何技能，将跳过执行");
            }
            logger.info("[MCP] ========== 阶段4: 执行技能管道 ==========");
            // 4. 执行 RAG 检索
            context.setState(McpState.RAG_RETRIEVAL);
            executeSkillPipeline(context, selectedSkills, sink);
            logger.info("[MCP] 技能管道执行完成, finalReply长度: {}", context.getFinalReply() != null ? context.getFinalReply().length() : 0);
            // 5. 质量检查
            if (context.isCheckEnabled() && context.getFinalReply() != null&&!context.getFinalReply().trim().isEmpty()) {
                logger.info("[MCP] ========== 阶段5: 质量检查 ==========");
                context.setState(McpState.QUALITY_CHECK);
                emit(sink, McpEvent.state("check", "质量检查...", context.getSessionId()));
                try{
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
                } catch (Exception e) {
                    logger.warn("[MCP] 质检异常,跳过质检", e.getMessage());
                    context.setCheckPassed(true);
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
     * 检查前置路由器
     * 按优先级遍历所有路由器，返回第一个匹配的结果
     */
    private RouteResult checkPreRouters(McpContext context) {
        for (PreRouter router : preRouters) {
            if (!router.isEnabled()) {
                continue;
            }
            
            try {
                if (router.matches(context)) {
                    logger.info("[MCP] 路由器 {} 匹配成功", router.getName());
                    RouteResult result = router.route(context);
                    result.setReason(String.format("[%s] %s", router.getName(), result.getReason()));
                    return result;
                }
            } catch (Exception e) {
                logger.warn("[MCP] 路由器 {} 执行异常: {}", router.getName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 处理路由结果
     * @return true 表示已处理完成，应终止后续流程
     */
    private boolean handleRouteResult(McpContext context, RouteResult routeResult, Sinks.Many<McpEvent> sink) {
        switch (routeResult.getType()) {
            case DIRECT_ANSWER:
                // 直接返回答案
                context.setFinalReply(routeResult.getAnswer());
                context.putSkillResult("routed", true);
                context.putSkillResult("routerReason", routeResult.getReason());
                emitFinalReply(context, sink);
                return true;
                
            case CALL_TOOL:
                // 调用工具
                return callTool(context, routeResult, sink);
                
            case NEED_MORE_INFO:
                // 需要更多信息
                context.setFinalReply("抱歉，我需要更多信息才能回答您的问题。请提供更多细节。");
                emitFinalReply(context, sink);
                return true;
                
            case CONTINUE:
            default:
                // 继续正常流程
                return false;
        }
    }

    /**
     * 调用工具
     */
    private boolean callTool(McpContext context, RouteResult routeResult, Sinks.Many<McpEvent> sink) {
        String toolName = routeResult.getToolName();
        Map<String, Object> params = routeResult.getToolParams();
        
        logger.info("[MCP] 调用工具: {}, 参数: {}", toolName, params);
        emit(sink, McpEvent.state(toolName, String.format("调用工具: %s", toolName), context.getSessionId()));
        
        // 查找对应的 Skill
        Skill targetSkill = null;
        for (Skill skill : skills) {
            if (skill.getName().equalsIgnoreCase(toolName) ||
                skill.getName().toLowerCase().contains(toolName.toLowerCase())) {
                targetSkill = skill;
                break;
            }
        }
        
        if (targetSkill == null) {
            logger.warn("[MCP] 未找到工具: {}, 使用默认回复", toolName);
            context.setFinalReply(String.format("抱歉，工具 %s 暂时不可用。", toolName));
            emitFinalReply(context, sink);
            return true;
        }
        
        try {
            // 构建参数
            SkillParams skillParams = new SkillParams();
            if (params != null) {
                skillParams.putAll(params);
            }
            skillParams.put("userMessage", context.getUserMessage());
            skillParams.put("userId", context.getUserId());
            skillParams.put("sessionId", context.getSessionId());
            
            // 执行技能
            SkillResult result = targetSkill.execute(context, skillParams);
            
            if (result.isSuccess()) {
                context.setFinalReply(result.getOutput());
                context.putSkillResult("routed", true);
                context.putSkillResult("toolName", toolName);
                if (result.getSources() != null) {
                    context.putSkillResult("sources", result.getSources());
                }
                emit(sink, McpEvent.complete(toolName, "工具调用成功", context.getSessionId()));
            } else {
                context.setFinalReply(String.format("工具调用失败: %s", result.getError()));
                emit(sink, McpEvent.complete(toolName, String.format("工具调用失败: %s", result.getError()), context.getSessionId()));
            }
            
            emitFinalReply(context, sink);
            return true;
            
        } catch (Exception e) {
            logger.error("[MCP] 工具调用异常: {}", e.getMessage(), e);
            context.setFinalReply(String.format("工具调用异常: %s", e.getMessage()));
            emitFinalReply(context, sink);
            return true;
        }
    }

    /**
     * 检查历史记忆命中（使用相似度匹配）
     */
    private boolean checkMemoryHit(McpContext context) {
        try {
            // 使用 QaMemoryService 进行相似度匹配（而非仅精确哈希匹配）
            Optional<QaMemoryEntry> cached = qaMemoryService.findSimilarAnswer(
                    context.getUserMessage(),
                    context.getUserId(),
                    null  // 意图在此时还未识别
            );
            
            if (cached.isPresent()) {
                QaMemoryEntry memory = cached.get();
                context.setFinalReply(memory.getAnswer());
                context.putSkillResult("cacheHit", true);
                context.putSkillResult("originalQuestion", memory.getQuestion());
                context.putSkillResult("accessCount", memory.getAccessCount());
                logger.info("[MCP] ✅ 相似问题命中！原始问题: {}, 访问次数: {}",
                        memory.getQuestion(), memory.getAccessCount());
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
                    // 流式执行（添加超时，避免无限等待）
                    List<SkillEvent> events = skill.executeStream(context, params)
                            .doOnNext(e -> {
                                if (e.getType() == SkillEvent.EventType.CHUNK) {
                                    emit(sink, McpEvent.chunk(skill.getName(),
                                            String.valueOf(e.getData()), context.getSessionId()));
                                }
                            })
                            .collectList()
                            .block(Duration.ofSeconds(30));  // 添加30秒超时
                    
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
        if (context.getFinalReply() == null || context.getFinalReply().trim().isEmpty() && context.isWebSearchEnabled()) {
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
        String reply = context.getFinalReply();
        // 检查空字符串和null
        if (reply != null && !reply.trim().isEmpty()) {
            emit(sink, McpEvent.finalReply(reply, context.getSources(), context.getSessionId()));
        } else {
            // 没有有效回复时发送提示消息
            logger.warn("[MCP] 最终回复为空，发送默认提示消息");
            emit(sink, McpEvent.finalReply("抱歉，我无法找到相关信息来回答您的问题。请尝试换一种问法或提供更多上下文。", context.getSources(), context.getSessionId()));
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
