package com.yizhaoqi.smartpai.mcp.router.impl;

import com.yizhaoqi.smartpai.config.properties.RouterProperties;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.router.PreRouter;
import com.yizhaoqi.smartpai.mcp.router.RouteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 网络搜索路由器
 * 当用户需要实时信息或网络搜索时，直接调用搜索工具
 *
 * 支持的问题类型：
 * - "搜索最新的AI新闻"
 * - "帮我查一下今天的天气"
 * - "搜索xxx"
 * - "最新xxx消息"
 */
@Component
public class WebSearchRouter implements PreRouter {

    @Autowired
    private RouterProperties routerProperties;

    // 触发网络搜索的关键词
    private static final List<String> SEARCH_KEYWORDS = Arrays.asList(
            "搜索", "查找", "查询", "查一下", "帮我查",
            "最新", "最近", "今天", "实时",
            "新闻", "消息", "资讯",
            "天气", "股价", "汇率", "油价"
    );

    // 需要实时信息的模式
    private static final List<Pattern> REALTIME_PATTERNS = Arrays.asList(
            Pattern.compile("(搜索|查一下|帮我查).+"),
            Pattern.compile("(最新|最近).*(新闻|消息|资讯)"),
            Pattern.compile("(今天|现在).*(天气|气温)"),
            Pattern.compile(".*(股价|股票|行情).*"),
            Pattern.compile(".*(汇率|换算).*"),
            Pattern.compile(".*(油价|汽油).*")
    );

    @Override
    public String getName() {
        return "WebSearchRouter";
    }

    @Override
    public String getDescription() {
        return "处理需要网络搜索的问题，如'搜索最新新闻'、'查天气'等";
    }

    @Override
    public int getOrder() {
        return 20;  // 次高优先级
    }

    @Override
    public boolean isEnabled() {
        return routerProperties.isRouterEnabled(getName());
    }

    @Override
    public boolean matches(McpContext context) {
        String message = context.getUserMessage().toLowerCase().trim();
        
        // 检查是否包含搜索关键词
        for (String keyword : SEARCH_KEYWORDS) {
            if (message.contains(keyword)) {
                // 进一步验证是否需要网络搜索
                for (Pattern pattern : REALTIME_PATTERNS) {
                    if (pattern.matcher(message).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public RouteResult route(McpContext context) {
        String message = context.getUserMessage().trim();
        
        // 提取搜索关键词
        String searchQuery = extractSearchQuery(message);
        
        // 构建工具参数
        Map<String, Object> params = new HashMap<>();
        params.put("query", searchQuery);
        params.put("limit", 5);
        
        return RouteResult.callTool("WebSearchTool", params, "需要实时网络信息，调用搜索工具");
    }

    /**
     * 从问题中提取搜索关键词
     */
    private String extractSearchQuery(String message) {
        // 移除常见的触发词
        String query = message
                .replaceAll("(搜索|查一下|帮我查|查找|查询)", "")
                .replaceAll("(最新|最近|今天|现在|实时)", "")
                .replaceAll("(新闻|消息|资讯|信息)", "")
                .trim();
        
        // 如果提取后为空，返回原消息
        if (query.isEmpty()) {
            return message;
        }
        
        return query;
    }
}
