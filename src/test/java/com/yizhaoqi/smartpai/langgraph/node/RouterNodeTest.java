package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.properties.AiProperties;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RouterNodeTest {

    private RouterNode routerNode;
    private LangChain4jChatService chatService;

    @BeforeEach
    void setUp() {
        routerNode = new RouterNode();
        chatService = Mockito.mock(LangChain4jChatService.class);

        AiProperties aiProperties = new AiProperties();
        aiProperties.getAgent().getIntent().setTemplate("{message}");

        ReflectionTestUtils.setField(routerNode, "chatService", chatService);
        ReflectionTestUtils.setField(routerNode, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(routerNode, "aiProperties", aiProperties);
    }

    @Test
    void shouldParseIntentAndSetRouteDecision() {
        when(chatService.chat(anyString()))
                .thenReturn("{\"intent\":\"SEARCH\",\"keywords\":[\"java\"],\"complexity\":2,\"needsHistory\":false,\"confidence\":0.95}");

        AIState state = new AIState("s1", "u1", "java question");
        AIState result = routerNode.apply(state);

        assertNotNull(result.getIntent());
        assertEquals("SEARCH", result.getIntent().getIntent());
        assertEquals("action", result.getRouteDecision());
    }
}
