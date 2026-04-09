package com.yizhaoqi.smartpai.config;

/**
 * @version 1.0
 * @Author ershiqijian
 * @Date 2026/4/5 0:08
 * @注释
 */

import com.yizhaoqi.smartpai.service.agent.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

//    @Bean
//    public IntentAgent intentAgent() {
//        return new IntentAgent();
//    }
//
//    @Bean
//    public WorkAgent workAgent() {
//        return new WorkAgent();
//    }
//
//    @Bean
//    public CheckAgent checkAgent() {
//        return new CheckAgent();
//    }

    @Bean
    public AgentOrchestrator agentOrchestrator(
            IntentAgent intentAgent,
            WorkAgent workAgent,
            CheckAgent checkAgent) {
        return new AgentOrchestrator(intentAgent, workAgent, checkAgent);
    }
}