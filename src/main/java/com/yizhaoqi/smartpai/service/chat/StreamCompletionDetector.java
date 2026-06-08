package com.yizhaoqi.smartpai.service.chat;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects streaming completion by observing response length stability.
 */
@Service
public class StreamCompletionDetector {

    private static final Logger logger = LoggerFactory.getLogger(StreamCompletionDetector.class);

    private static final long INITIAL_DELAY_MS = 3000;
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long TIMEOUT_MS = 30000;
    private static final int STABLE_ROUNDS_THRESHOLD = 2;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "chat-stream-completion-detector");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<String> awaitCompletion(String sessionId, StringBuilder responseBuilder) {
        CompletableFuture<String> completionFuture = new CompletableFuture<>();

        if (responseBuilder == null) {
            completionFuture.complete("");
            return completionFuture;
        }

        AtomicInteger lastLength = new AtomicInteger(-1);
        AtomicInteger stableRounds = new AtomicInteger(0);

        ScheduledFuture<?> pollingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                int currentLength = responseBuilder.length();
                if (currentLength <= 0) {
                    return;
                }

                int previousLength = lastLength.getAndSet(currentLength);
                if (previousLength == currentLength) {
                    int rounds = stableRounds.incrementAndGet();
                    if (rounds >= STABLE_ROUNDS_THRESHOLD && !completionFuture.isDone()) {
                        logger.info("Detected stream completion by stability, sessionId={}, length={}", sessionId, currentLength);
                        completionFuture.complete(responseBuilder.toString());
                    }
                } else {
                    stableRounds.set(0);
                }
            } catch (Exception e) {
                if (!completionFuture.isDone()) {
                    completionFuture.completeExceptionally(e);
                }
            }
        }, INITIAL_DELAY_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (!completionFuture.isDone()) {
                logger.warn("Stream completion timed out, fallback returning current buffer, sessionId={}, length={}",
                        sessionId, responseBuilder.length());
                completionFuture.complete(responseBuilder.toString());
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);

        completionFuture.whenComplete((result, error) -> {
            pollingTask.cancel(false);
            timeoutTask.cancel(false);
        });

        return completionFuture;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
