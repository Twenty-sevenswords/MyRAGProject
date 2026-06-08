package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.service.agent.tools.KnowledgeBaseTool;
import com.yizhaoqi.smartpai.service.agent.tools.SystemTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Tool-based Agent 服务
 *
 * 基于 LangChain4j AiServices + @Tool 实现的 ReAct 风格 Agent。
 * Agent 自主决策调用哪些工具，无需手动编排工具调用顺序。
 *
 * 与 AgentOrchestrator 的区别：
 * - AgentOrchestrator：手动编排（Intent→Work→Check），流程固定
 * - ToolBasedAgentService：LLM 自主 ReAct 循环，工具调用由模型决策
 *
 * 适用场景：
 * - 复杂多步骤问题（需要多次检索、组合信息）
 * - 工具选择不确定的场景
 */
@Service
public class ToolBasedAgentService {

    private static final Logger log = LoggerFactory.getLogger(ToolBasedAgentService.class);

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private KnowledgeBaseTool knowledgeBaseTool;

    @Autowired
    private SystemTool systemTool;

    @Autowired(required = false)
    private Timer agentPipelineTimer;

    private SmartPaiAgent agent;

    /**
     * LangChain4j AiServices 接口定义
     * 框架自动生成实现，注入工具和记忆
     */
    interface SmartPaiAgent {

        @SystemMessage("""
                你是派聪明（SmartPai）智能助手，一个企业级知识问答系统。

                你有以下工具可以使用：
                - searchKnowledgeBase：搜索内部知识库
                - listRelatedDocuments：列出相关文档
                - getCurrentDateTime：获取当前时间
                - generateFallbackReply：生成兜底回复

                工作原则：
                1. 优先使用 searchKnowledgeBase 检索知识库
                2. 如果知识库无结果，使用 generateFallbackReply 诚实告知
                3. 引用文档内容时，保留【来源#编号: 文件名】格式
                4. 不要编造知识库中不存在的信息
                5. 回答简洁专业，使用中文
                """)
        @UserMessage("用户ID：{{userId}}\n\n用户问题：{{question}}")
        String chat(@V("userId") String userId, @V("question") String question);
    }

    @PostConstruct
    public void init() {
        log.info("[ToolBasedAgentService] 初始化 Tool-based Agent...");

        this.agent = AiServices.builder(SmartPaiAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(knowledgeBaseTool, systemTool)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        log.info("[ToolBasedAgentService] Tool-based Agent 初始化完成");
    }

    /**
     * 使用 Tool-based Agent 回答问题
     *
     * @param userId   用户ID（用于工具内权限过滤）
     * @param question 用户问题
     * @return Agent 回复
     */
    public String chat(String userId, String question) {
        log.info("[ToolBasedAgentService] 开始处理: userId={}, question={}", userId, question);

        if (agentPipelineTimer != null) {
            return agentPipelineTimer.record(() -> doChat(userId, question));
        }
        return doChat(userId, question);
    }

    private String doChat(String userId, String question) {
        try {
            String reply = agent.chat(userId, question);
            log.info("[ToolBasedAgentService] 回复生成完成，长度: {}", reply != null ? reply.length() : 0);
            return reply;
        } catch (Exception e) {
            log.error("[ToolBasedAgentService] Agent 执行失败: {}", e.getMessage(), e);
            return "系统暂时无法处理您的请求，请稍后重试。";
        }
    }
}
