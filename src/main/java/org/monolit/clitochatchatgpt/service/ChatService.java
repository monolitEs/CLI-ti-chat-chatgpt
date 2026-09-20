package org.monolit.clitochatchatgpt.service;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final CodexAppServerClient codex;
    private final Duration timeout;
    private final Duration interruptGrace;
    private final ExecutorService executor;
    private final AtomicBoolean active = new AtomicBoolean();

    @Autowired
    public ChatService(CodexAppServerClient codex,
            @Value("${chat.timeout:PT10M}") Duration timeout,
            @Value("${chat.interrupt-grace:PT2S}") Duration interruptGrace) {
        this(codex, timeout, interruptGrace, Executors.newVirtualThreadPerTaskExecutor());
    }

    ChatService(CodexAppServerClient codex, Duration timeout, Duration interruptGrace,
            ExecutorService executor) {
        this.codex = codex;
        this.timeout = timeout;
        this.interruptGrace = interruptGrace;
        this.executor = executor;
    }

    public String chat(String requestId, String message) {
        if (!active.compareAndSet(false, true)) {
            throw new ChatException(ChatFailure.BUSY);
        }
        Future<String> task;
        try {
            task = executor.submit(() -> codex.chat(requestId, message));
        } catch (RuntimeException exception) {
            active.set(false);
            throw new ChatException(ChatFailure.UNAVAILABLE, exception);
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            interruptAndAwait(task);
            throw new ChatException(ChatFailure.TIMEOUT, exception);
        } catch (ExecutionException exception) {
            throw mapFailure(exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            codex.reset();
            throw new ChatException(ChatFailure.UNAVAILABLE, exception);
        } finally {
            task.cancel(true);
            active.set(false);
        }
    }

    private void interruptAndAwait(Future<String> task) {
        codex.interruptActiveTurn();
        try {
            task.get(interruptGrace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            codex.reset();
        } catch (ExecutionException exception) {
            // A terminal failure after turn/interrupt makes the process safe to reuse.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            codex.reset();
        }
    }

    private static ChatException mapFailure(Throwable cause) {
        if (cause instanceof CodexException codexException) {
            var failure = switch (codexException.kind()) {
                case NOT_AUTHENTICATED -> ChatFailure.NOT_AUTHENTICATED;
                case UNAVAILABLE -> ChatFailure.UNAVAILABLE;
                case PROTOCOL, TURN_FAILED -> ChatFailure.BAD_GATEWAY;
            };
            return new ChatException(failure, cause);
        }
        return new ChatException(ChatFailure.UNAVAILABLE, cause);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

}
