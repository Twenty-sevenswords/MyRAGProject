package com.yizhaoqi.smartpai.langgraph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.dto.SearchResult;
import com.yizhaoqi.smartpai.langgraph.state.AIState;
import com.yizhaoqi.smartpai.service.LangChain4jChatService;
import com.yizhaoqi.smartpai.util.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class GradingNodeTest {

    private GradingNode gradingNode;
    private LangChain4jChatService chatService;

    @BeforeEach
    void setUp() {
        gradingNode = new GradingNode();
        chatService = Mockito.mock(LangChain4jChatService.class);

        ReflectionTestUtils.setField(gradingNode, "chatService", chatService);
        ReflectionTestUtils.setField(gradingNode, "promptLoader", new PromptLoader());
        ReflectionTestUtils.setField(gradingNode, "objectMapper", new ObjectMapper());
    }

    @Test
    void shouldKeepOnlyHighRelevanceDocuments() {
        when(chatService.chat(anyString()))
                .thenReturn("{\"score\":9}")
                .thenReturn("{\"score\":3}");

        AIState state = new AIState("s1", "u1", "question");
        state.setSearchResults(List.of(
                new SearchResult("f1", 1, "high relevance", 0.9),
                new SearchResult("f2", 2, "low relevance", 0.6)
        ));

        AIState result = gradingNode.apply(state);

        assertEquals(1, result.getGradedResults().size());
        assertFalse(result.isSufficientContext());
    }

    @Test
    void shouldFallbackToRegexWhenJsonInvalid() {
        when(chatService.chat(anyString())).thenReturn("score is 8 points");

        AIState state = new AIState("s1", "u1", "question");
        state.setSearchResults(List.of(new SearchResult("f1", 1, "doc", 0.8)));

        AIState result = gradingNode.apply(state);
        assertEquals(1, result.getGradedResults().size());
        assertTrue(result.getGradedResults().get(0).getFileMd5().equals("f1"));
    }
}
