package com.yizhaoqi.smartpai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LangChain4j 聊天服务
 * 封装 LangChain4j 的聊天能力，提供同步和流式调用
 */
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

    /**
     * 简单对话
     */
    public String chat(String userMessage) {
        logger.debug("[LangChain4j] 同步调用: {}", userMessage);
        return chatModel.generate(userMessage);
    }

    /**
     * 带系统提示的对话
     */
    public String chat(String systemPrompt, String userMessage) {
        logger.debug("[LangChain4j] 带系统提示调用: system={}, user={}", 
                systemPrompt.length(), userMessage.length());
        
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));
        
        Response<AiMessage> response = chatModel.generate(messages);
        return response.content().text();
    }

    /**
     * 带历史消息的对话
     */
    public String chat(String systemPrompt, String userMessage,
                       List<Map<String, String>> history) {
        logger.debug("[LangChain4j] 带历史消息调用: historySize={}", history.size());
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        
        // 添加历史消息
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
        
        // 添加当前用户消息
        messages.add(UserMessage.from(userMessage));
        
        Response<AiMessage> response = chatModel.generate(messages);
        return response.content().text();
    }

    // ========== 流式调用 ==========

    /**
     * 流式对话
     */
    public void streamChat(String userMessage, Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用: {}", userMessage);
        
        streamingChatModel.generate(userMessage, new StreamingResponseHandler<AiMessage>() {
            private final StringBuilder fullResponse = new StringBuilder();

            @Override
            public void onNext(String token) {
                fullResponse.append(token);
                onChunk.accept(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                logger.debug("[LangChain4j] 流式调用完成");
            }

            @Override
            public void onError(Throwable error) {
                logger.error("[LangChain4j] 流式调用错误", error);
                onError.accept(error);
            }
        });
    }

    /**
     * 流式对话（带系统提示）
     */
    public void streamChat(String systemPrompt, String userMessage, 
                           Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用（带系统提示）");
        
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));
        
        streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            private final StringBuilder fullResponse = new StringBuilder();

            @Override
            public void onNext(String token) {
                fullResponse.append(token);
                onChunk.accept(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                logger.debug("[LangChain4j] 流式调用完成，总长度: {}", fullResponse.length());
            }

            @Override
            public void onError(Throwable error) {
                logger.error("[LangChain4j] 流式调用错误", error);
                onError.accept(error);
            }
        });
    }

    /**
     * 流式对话（带系统提示和历史消息，使用 Consumer 回调）
     */
    public void streamChat(String systemPrompt, String userMessage,
                           List<Map<String, String>> history,
                           Consumer<String> onChunk, Consumer<Throwable> onError) {
        logger.debug("[LangChain4j] 流式调用（带系统提示和历史消息）");
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        
        // 添加历史消息
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
        
        // 添加当前用户消息
        messages.add(UserMessage.from(userMessage));
        
        streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            private final StringBuilder fullResponse = new StringBuilder();

            @Override
            public void onNext(String token) {
                fullResponse.append(token);
                onChunk.accept(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                logger.debug("[LangChain4j] 流式调用完成，总长度: {}", fullResponse.length());
            }

            @Override
            public void onError(Throwable error) {
                logger.error("[LangChain4j] 流式调用错误", error);
                onError.accept(error);
            }
        });
    }

    /**
     * 流式对话（返回 Flux）
     */
    public Flux<String> streamChatFlux(String systemPrompt, String userMessage) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));
        
        streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                sink.tryEmitNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                sink.tryEmitError(error);
            }
        });
        
        return sink.asFlux();
    }

    /**
     * 流式对话（带历史消息，返回 Flux）
     */
    public Flux<String> streamChatFlux(String systemPrompt, String userMessage,
                                        List<Map<String, String>> history) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 添加系统提示
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        
        // 添加历史消息
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
        
        // 添加当前用户消息
        messages.add(UserMessage.from(userMessage));
        
        streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                sink.tryEmitNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
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
