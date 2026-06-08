package com.yizhaoqi.smartpai.service.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.util.PromptLoader;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class IntentRecognitionServiceTest {

    private ChatLanguageModel chatModel;
    private IntentRecognitionService service;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatLanguageModel.class);
        service = new IntentRecognitionService(chatModel, new ObjectMapper(), new PromptLoader());
    }

    @Test
    void shouldParseIntentFromJsonResponse() {
        when(chatModel.chat(anyString()))
                .thenReturn("{\"intent\":\"SEARCH\",\"confidence\":0.93,\"keywords\":[\"java\",\"spring\"],\"complexity\":2,\"needsHistory\":false}");

        IntentRecognitionService.IntentRecognitionResult result = service.recognizeIntent("spring search");
        assertEquals("SEARCH", result.getIntent());
        assertEquals(2, result.getComplexity());
        assertTrue(result.getKeywords().contains("java"));
    }

    @Test
    void shouldFallbackWhenJsonIsInvalid() {
        when(chatModel.chat(anyString())).thenReturn("Intent: SEARCH");

        IntentRecognitionService.IntentRecognitionResult result = service.recognizeIntent("find docs");
        assertEquals("SEARCH", result.getIntent());
    }
}
