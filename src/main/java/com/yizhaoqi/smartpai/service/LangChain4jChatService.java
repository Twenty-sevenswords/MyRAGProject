package com.yizhaoqi.smartpai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class LangChain4jChatService {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jChatService.class);

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;

    public LangChain4jChatService(ChatLanguageModel chatModel,
                                   StreamingChatLanguageModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    // ========== 同步调用 ==========

    public String chat(String userMessage) {
        logger.debug("[LangChain4j] 同步调用: {}", userMessage);
        return chatModel.chat(userMessage);
    }

    public String chat(String systemPrompt, String userMessage) {
        logger.debug("[LangChain4j] 带系统提示调用: system={}, user={}",
                systemPrompt.length(), userMessage.length());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));

        ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
        return response.aiMessage().text();
    }

    public String chat(String systemPrompt, String userMessage,
                       List<Map<String, String>> history) {
        logger.debug("[LangChain4j] 带历史消息调用: historySize={}", history.size());

        List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);

        ChatResponse response = chatModel.chat(ChatRequest.builder().messages(messages).build());
        return response.aiMessage().text();
    }

    // ========== 流式调用 ==========

    public void streamChat(String userMessage, Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用: {}", userMessage);

        List<ChatMessage> messages = List.of(UserMessage.from(userMessage));
        doStreamChat(messages, onChunk, onError);
    }

    public void streamChat(String systemPrompt, String userMessage,
                           Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用（带系统提示）");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));
        doStreamChat(messages, onChunk, onError);
    }

    public void streamChat(String systemPrompt, String userMessage,
                           List<Map<String, String>> history,
                           Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用（带系统提示和历史消息）");

        List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);
        doStreamChat(messages, onChunk, onError);
    }

    public Flux<String> streamChatFlux(String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));
        return doStreamChatFlux(messages);
    }

    public Flux<String> streamChatFlux(String systemPrompt, String userMessage,
                                        List<Map<String, String>> history) {
        List<ChatMessage> messages = buildMessages(systemPrompt, userMessage, history);
        return doStreamChatFlux(messages);
    }

    // ========== 内部方法 ==========

    private List<ChatMessage> buildMessages(String systemPrompt, String userMessage,
                                            List<Map<String, String>> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        if (history != null) {
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    messages.add(UserMessage.from(content));
                } else if ("assistant".equals(role)) {
                    messages.add(AiMessage.from(content));
                }
            }
        }
        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    private void doStreamChat(List<ChatMessage> messages,
                              Consumer<String> onChunk, Consumer<Throwable> onError) {
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onChunk.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                logger.debug("[LangChain4j] 流式调用完成");
            }

            @Override
            public void onError(Throwable error) {
                logger.error("[LangChain4j] 流式调用错误", error);
                onError.accept(error);
            }
        });
    }

    private Flux<String> doStreamChatFlux(List<ChatMessage> messages) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }
}
