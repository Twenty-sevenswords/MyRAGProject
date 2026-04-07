package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.service.agent.AgentOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class MultiAgentChatService {

    @Autowired
    private AgentOrchestrator orchestrator;

    public Flux<AgentOrchestrator.AgentEvent> chat(String message, String sessionId, String userId) {
        return orchestrator.orchestrate(message, sessionId, userId);
    }
}