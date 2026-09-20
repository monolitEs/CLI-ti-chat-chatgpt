package org.monolit.clitochatchatgpt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;

class ChatServiceTests {

    private ChatService service;

    @AfterEach
    void closeExecutor() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void rejectsSecondRequestWhileFirstIsActive() throws Exception {
        var codex = mock(CodexAppServerClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(codex.chat("req-1", "first")).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return "done";
        });
        service = create(codex, Duration.ofSeconds(2), Duration.ofMillis(20));

        var caller = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = caller.submit(() -> service.chat("req-1", "first"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.chat("req-2", "second"))
                    .isInstanceOfSatisfying(ChatException.class,
                            exception -> assertThat(exception.failure())
                                    .isEqualTo(ChatFailure.BUSY));

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("done");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void interruptsAndResetsCodexAfterTimeoutWithoutTerminalEvent() throws Exception {
        var codex = mock(CodexAppServerClient.class);
        var blocked = new CountDownLatch(1);
        when(codex.chat("req-1", "slow")).thenAnswer(invocation -> {
            blocked.await();
            return "late";
        });
        service = create(codex, Duration.ofMillis(20), Duration.ofMillis(20));

        assertThatThrownBy(() -> service.chat("req-1", "slow"))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(ChatFailure.TIMEOUT));

        verify(codex).interruptActiveTurn();
        verify(codex).reset();
    }

    private static ChatService create(CodexAppServerClient codex, Duration timeout,
            Duration grace) {
        return new ChatService(codex, timeout, grace, Executors.newVirtualThreadPerTaskExecutor());
    }
}
