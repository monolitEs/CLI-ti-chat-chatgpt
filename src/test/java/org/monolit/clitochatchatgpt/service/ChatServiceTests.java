package org.monolit.clitochatchatgpt.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.dto.ModelSettingsResponse;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.persistence.ConversationStore;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatServiceTests {

    private ChatService service;

    @AfterEach
    void closeExecutor() {
        if (service != null) {
            service.close();
        }
    }

    private static ChatService create(CodexAppServerClient codex, ConversationService conversations, Duration timeout,
            Duration grace) {
        return new ChatService(codex, conversations, timeout, grace, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void rejectsSecondRequestWhileFirstIsActive() throws Exception {
        var codex = mock(CodexAppServerClient.class);
        var conversations = mock(ConversationService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(conversations.chat("req-1", "first", "dialog-a")).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return "done";
        });
        service = create(codex, conversations, Duration.ofSeconds(2), Duration.ofMillis(20));

        var caller = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = caller.submit(() -> service.chat("req-1", "first", "dialog-a"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.chat("req-2", "second", "dialog-b"))
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
        var conversations = mock(ConversationService.class);
        var blocked = new CountDownLatch(1);
        when(conversations.chat("req-1", "slow", null)).thenAnswer(invocation -> {
            blocked.await();
            return "late";
        });
        service = create(codex, conversations, Duration.ofMillis(20), Duration.ofMillis(20));

        assertThatThrownBy(() -> service.chat("req-1", "slow"))
                .isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(ChatFailure.TIMEOUT));

        verify(codex).interruptActiveTurn();
        verify(codex).reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "start", "save"})
    void keepsPreparationInsideSingleFlightAndTimeout(String phase) throws Exception {
        var codex = mock(CodexAppServerClient.class);
        var store = mock(ConversationStore.class);
        var entered = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        Answer<Object> block = invocation -> {
            entered.countDown();
            try {
                blocked.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted test preparation", exception);
            }
            return null;
        };
        when(codex.startThread(false)).thenReturn("thr_1");
        switch (phase) {
            case "read" -> when(store.find("a")).thenAnswer(block);
            case "start" -> when(codex.startThread(false)).thenAnswer(block);
            case "save" -> doAnswer(block).when(store).save("a", "thr_1");
            default -> throw new IllegalArgumentException(phase);
        }
        service = create(codex, new ConversationService(codex, store),
                Duration.ofMillis(300), Duration.ofMillis(20));

        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = caller.submit(() -> service.chat("req-1", "slow", "a"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.chat("req-2", "second", "b"))
                    .isInstanceOfSatisfying(ChatException.class,
                            exception -> assertThat(exception.failure()).isEqualTo(ChatFailure.BUSY));
            assertThatThrownBy(() -> first.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ChatException.class)
                    .satisfies(exception -> assertThat(((ChatException) exception.getCause()).failure())
                            .isEqualTo(ChatFailure.TIMEOUT));
        }
        verify(codex).interruptActiveTurn();
        verify(codex, org.mockito.Mockito.atLeastOnce()).reset();
        verify(codex, never()).runTurn(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "put", "chat"})
    void allOperationsShareOneSlot(String operation) throws Exception {
        var codex = mock(CodexAppServerClient.class);
        var conversations = mock(ConversationService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Answer<Object> block = invocation -> {
            entered.countDown();
            release.await();
            return operation.equals("chat") ? "done" : new ModelSettingsResponse("req", "a", "m", "high");
        };
        switch (operation) {
            case "get" -> when(conversations.getSettings("req", "a")).thenAnswer(block);
            case "put" -> when(conversations.updateSettings("req", "a", "m", "high")).thenAnswer(block);
            default -> when(conversations.chat("req", "msg", "a")).thenAnswer(block);
        }
        service = create(codex, conversations, Duration.ofSeconds(3), Duration.ofMillis(20));
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = caller.submit(() -> switch (operation) {
                case "get" -> service.getSettings("req", "a");
                case "put" -> service.updateSettings("req", "a", "m", "high");
                default -> service.chat("req", "msg", "a");
            });
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.getSettings("other", "b"))
                        .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.failure()).isEqualTo(ChatFailure.BUSY));
                assertThatThrownBy(() -> service.updateSettings("other", "b", "m", "low"))
                        .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.failure()).isEqualTo(ChatFailure.BUSY));
                assertThatThrownBy(() -> service.chat("other", "msg", "b"))
                        .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.failure()).isEqualTo(ChatFailure.BUSY));
            } finally {
                release.countDown();
            }
            assertThat(first.get(1, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    void settingsTimeoutUsesExistingInterruptResetPathAndReleasesSlot() throws IOException {
        var codex = mock(CodexAppServerClient.class);
        var store = mock(ConversationStore.class);
        when(store.find("a")).thenReturn("t");
        when(codex.readSettings("t")).thenAnswer(invocation -> {
            new CountDownLatch(1).await();
            return null;
        });
        service = create(codex, new ConversationService(codex, store), Duration.ofMillis(50), Duration.ofMillis(20));
        assertThatThrownBy(() -> service.getSettings("req", "a"))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.failure()).isEqualTo(ChatFailure.TIMEOUT));
        verify(codex).interruptActiveTurn();
        verify(codex, atLeastOnce()).reset();
        assertThatThrownBy(() -> service.getSettings("next", "missing"))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.failure()).isEqualTo(ChatFailure.CONVERSATION_NOT_FOUND));
    }
}
