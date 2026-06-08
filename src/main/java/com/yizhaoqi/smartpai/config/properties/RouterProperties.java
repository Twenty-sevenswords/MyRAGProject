package com.yizhaoqi.smartpai.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 前置路由器配置
 */
@Component
@ConfigurationProperties(prefix = "mcp.router")
@Data
public class RouterProperties {

    /**
     * 是否启用前置路由功能
     */
    private boolean enabled = true;

    /**
     * 各路由器的启用状态配置
     * key: 路由器名称
     * value: 是否启用
     */
    private Map<String, Boolean> routers = new HashMap<>();

    /**
     * 检查指定路由器是否启用
     * @param routerName 路由器名称
     * @return true 表示启用
     */
    public boolean isRouterEnabled(String routerName) {
        if (!enabled) {
            return false;
        }
        // 如果没有配置，默认启用
        return routers.getOrDefault(routerName, true);
    }
}
