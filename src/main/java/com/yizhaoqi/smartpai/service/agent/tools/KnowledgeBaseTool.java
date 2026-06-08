package com.yizhaoqi.smartpai.service.agent.tools;

import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具
 *
 * 通过 LangChain4j @Tool 注解暴露给 AI Agent，
 * Agent 可自主决策何时调用此工具检索内部知识库。
 *
 * 工具设计原则：
 * - 描述清晰，让 LLM 能准确判断何时调用
 * - 参数简单，避免 LLM 生成错误参数
 * - 返回结构化文本，便于 LLM 理解和引用
 */
@Component
public class KnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    @Autowired
    private HybridSearchService hybridSearchService;

    /**
     * 搜索内部知识库，返回相关文档片段。
     * 适用于：查询公司内部文档、上传的文件、知识库内容。
     */
    @Tool("搜索内部知识库，返回与查询最相关的文档片段。当用户询问知识库中的内容时使用此工具。")
    public String searchKnowledgeBase(
            @P("用户的搜索查询，应为精确的关键词或问题") String query,
            @P("当前用户ID，用于权限过滤") String userId) {

        log.info("[KnowledgeBaseTool] 检索知识库: query='{}', userId='{}'", query, userId);

        try {
            List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, 5);

            if (results.isEmpty()) {
                return "知识库中未找到与「" + query + "」相关的内容。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(results.size()).append(" 条相关内容：\n\n");

            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                String fileName = r.getFileName() != null ? r.getFileName() : "未知文件";
                sb.append("【来源#").append(i + 1).append(": ").append(fileName).append("】\n");
                sb.append(r.getTextContent()).append("\n\n");
            }

            log.info("[KnowledgeBaseTool] 检索完成，返回 {} 条结果", results.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 检索失败: {}", e.getMessage(), e);
            return "知识库检索暂时不可用，请稍后重试。";
        }
    }

    /**
     * 列出知识库中与主题相关的文档名称。
     * 适用于：用户想了解知识库中有哪些文档时。
     */
    @Tool("列出知识库中与指定主题相关的文档名称列表。当用户询问'有哪些文档'或'知识库里有什么'时使用。")
    public String listRelatedDocuments(
            @P("主题关键词") String topic,
            @P("当前用户ID") String userId) {

        log.info("[KnowledgeBaseTool] 列出相关文档: topic='{}', userId='{}'", topic, userId);

        try {
            List<SearchResult> results = hybridSearchService.searchWithPermission(topic, userId, 10);

            if (results.isEmpty()) {
                return "知识库中未找到与「" + topic + "」相关的文档。";
            }

            String docList = results.stream()
                    .map(r -> r.getFileName() != null ? r.getFileName() : "未知文件")
                    .distinct()
                    .collect(Collectors.joining("\n- ", "相关文档列表：\n- ", ""));

            return docList;

        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 列出文档失败: {}", e.getMessage(), e);
            return "获取文档列表失败，请稍后重试。";
        }
    }
}
