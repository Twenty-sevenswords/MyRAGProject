package com.yizhaoqi.smartpai.service.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.util.PromptLoader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class QualityCheckServiceTest {

    private ChatLanguageModel chatModel;
    private QualityCheckService service;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatLanguageModel.class);
        service = new QualityCheckService(chatModel, new PromptLoader(), new ObjectMapper());
    }

    @Test
    void shouldParseJsonQualityResult() {
        ChatResponse response = Mockito.mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("{\"passed\":true,\"score\":88,\"reason\":\"good\"}"));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        QualityCheckService.QualityCheckResult result = service.checkQuality("q", "a");
        assertTrue(result.isPassed());
        assertEquals(88, result.getScore());
    }

    @Test
    void shouldFallbackWhenResponseIsNotJson() {
        ChatResponse response = Mockito.mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("passed=true score: 72"));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        QualityCheckService.QualityCheckResult result = service.checkQuality("q", "a");
        assertEquals(72, result.getScore());
    }
}
