package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 问答记忆配置类
 * 用于配置历史问答记忆的相关参数
 */
@Component
@ConfigurationProperties(prefix = "qa-memory")
@Data
public class QaMemoryProperties {

    /** 是否启用问答记忆功能 */
    private boolean enabled = true;

    /** 记忆有效期（天数），默认7天 */
    private int expireDays = 7;

    /** 相似度阈值（0.0-1.0），默认0.85，高于此值视为相同问题 */
    private double similarityThreshold = 0.85;

    /** 记忆键前缀 */
    private String keyPrefix = "qa:memory";

    /** 单个用户最大记忆条数 */
    private int maxMemoryPerUser = 100;

}
