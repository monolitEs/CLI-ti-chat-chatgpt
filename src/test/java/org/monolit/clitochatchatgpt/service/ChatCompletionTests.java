package org.monolit.clitochatchatgpt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.dto.ChatMessage;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.persistence.ConversationStore;
import org.monolit.clitochatchatgpt.utils.FakeCodexProcess;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatCompletionTests {

    private static final List<ChatMessage> MESSAGES = List.of(new ChatMessage("system", "Policy"),
            new ChatMessage("developer", "Style"), new ChatMessage("user", " Old question \n"),
            new ChatMessage("assistant", "Old answer"), new ChatMessage("user", " New question "));
    @TempDir
    Path directory;

    private static void assertBusy(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ChatException.class,
                e -> assertThat(e.failure()).isEqualTo(ChatFailure.BUSY));
    }

    @Test
    void sendsNativeHistoryAndOneFinalUserInEphemeralThreadWithoutSaving() {
        var mapper = JsonMapper.builder().build();
        var process = new FakeCodexProcess("""
                {"id":1,"result":{}}
                {"id":2,"result":{"requiresOpenaiAuth":false}}
                {"id":3,"result":{"thread":{"id":"t","ephemeral":true},"model":"m"}}
                {"id":4,"result":{}}
                {"id":5,"result":{"turn":{"id":"turn"}}}
                {"method":"item/completed","params":{"threadId":"t","item":{"type":"agentMessage","text":"New answer"}}}
                {"method":"turn/completed","params":{"threadId":"t","turn":{"id":"turn","status":"completed"}}}
                {"id":6,"result":{"status":"unsubscribed"}}
                """);
        var client = process.client(mapper);
        var store = mock(ConversationStore.class);
        var conversations = new ConversationService(client, store);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var chat = new ChatService(client, conversations, Duration.ofSeconds(2), Duration.ofMillis(20), executor);
            assertThat(chat.complete("req", "m", MESSAGES)).isEqualTo("New answer");
        }
        var requests = process.requests().lines().map(mapper::readTree).toList();
        assertThat(requests.stream().map(r -> r.path("method").asString())).containsExactly(
                "initialize", "initialized", "account/read", "thread/start", "thread/inject_items", "turn/start", "thread/unsubscribe");
        var start = requests.get(3).path("params");
        assertThat(start.size()).isEqualTo(4);
        assertThat(start.path("ephemeral").asBoolean()).isTrue();
        assertThat(start.path("model").asString()).isEqualTo("m");
        assertThat(start.path("allowProviderModelFallback").asBoolean()).isFalse();
        var history = requests.get(4).path("params").path("items");
        assertThat(history.size()).isEqualTo(4);
        for (int index = 0; index < 4; index++) {
            var message = MESSAGES.get(index);
            assertThat(history.get(index).path("role").asString())
                    .isEqualTo("system".equals(message.role()) ? "developer" : message.role());
            assertThat(history.get(index).path("content").get(0).path("text").asString()).isEqualTo(message.content());
        }
        var input = requests.get(5).path("params").path("input");
        assertThat(input.size()).isEqualTo(1);
        assertThat(input.get(0).path("text").asString()).isEqualTo(" New question ");
        verifyNoInteractions(store);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void skipsInjectionForSingleUserAndCleansUpAfterSuccess() throws Exception {
        var client = mock(CodexAppServerClient.class);
        var store = mock(ConversationStore.class);
        when(client.startThread(true, "m")).thenReturn("t");
        when(client.runTurn("req", "Hello", "t")).thenReturn("Answer");
        assertThat(new ConversationService(client, store).complete("req", "m", List.of(new ChatMessage("user", "Hello"))))
                .isEqualTo("Answer");
        verify(client).unsubscribeThread("t");
        verify(client).injectHistory("t", List.of());
        verifyNoInteractions(store);
    }

    @Test
    void singleUserUsesANewEphemeralThreadOnEveryCall() throws Exception {
        var mapper = JsonMapper.builder().build();
        var process = new FakeCodexProcess("""
                {"id":1,"result":{}}
                {"id":2,"result":{"requiresOpenaiAuth":false}}
                {"id":3,"result":{"thread":{"id":"one","ephemeral":true},"model":"m"}}
                {"id":4,"result":{"thread":{"id":"two","ephemeral":true},"model":"m"}}
                """);
        var client = process.client(mapper);
        client.ensureStarted();
        assertThat(client.startThread(true, "m")).isEqualTo("one");
        client.injectHistory("one", List.of());
        assertThat(client.startThread(true, "m")).isEqualTo("two");
        client.injectHistory("two", List.of());
        assertThat(process.requests()).doesNotContain("thread/resume", "thread/inject_items");
        assertThat(process.requests().lines().filter(line -> line.contains("thread/start"))).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"thread\":{\"id\":\"t\",\"ephemeral\":false},\"model\":\"m\"}",
            "{\"thread\":{\"id\":\"t\",\"ephemeral\":true},\"model\":\"other\"}",
            "{\"thread\":{\"id\":\"t\",\"ephemeral\":true}}"})
    void rejectsPersistenceOrModelMismatchBeforeSendingMessages(String response) {
        var process = new FakeCodexProcess("{\"id\":1,\"result\":{}}\n"
                + "{\"id\":2,\"result\":{\"requiresOpenaiAuth\":false}}\n"
                + "{\"id\":3,\"result\":" + response + "}\n");
        var client = process.client(JsonMapper.builder().build());
        var store = mock(ConversationStore.class);
        assertThatThrownBy(() -> new ConversationService(client, store).complete("req", "m", MESSAGES))
                .isInstanceOfSatisfying(CodexException.class, e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
        assertThat(process.isAlive()).isFalse();
        assertThat(process.requests()).doesNotContain("thread/inject_items", "turn/start");
        verifyNoInteractions(store);
    }

    @Test
    void resetsOnRejectedInjectionBeforeStartingATurn() {
        var process = new FakeCodexProcess("""
                {"id":1,"result":{}}
                {"id":2,"result":{"requiresOpenaiAuth":false}}
                {"id":3,"result":{"thread":{"id":"t","ephemeral":true},"model":"m"}}
                {"id":4,"error":{"code":-32600,"message":"rejected"}}
                """);
        var client = process.client(JsonMapper.builder().build());
        var store = mock(ConversationStore.class);
        assertThatThrownBy(() -> new ConversationService(client, store).complete("req", "m", MESSAGES))
                .isInstanceOf(CodexException.class);
        assertThat(process.isAlive()).isFalse();
        assertThat(process.requests()).doesNotContain("turn/start");
        verifyNoInteractions(store);
    }

    @Test
    void completionUsesExistingTimeoutInterruptAndReset() {
        var client = mock(CodexAppServerClient.class);
        var conversations = mock(ConversationService.class);
        var blocked = new CountDownLatch(1);
        when(conversations.complete(anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            blocked.await();
            return "late";
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var chat = new ChatService(client, conversations, Duration.ofMillis(30), Duration.ofMillis(20), executor);
            assertThatThrownBy(() -> chat.complete("req", "m", MESSAGES)).isInstanceOfSatisfying(ChatException.class,
                    e -> assertThat(e.failure()).isEqualTo(ChatFailure.TIMEOUT));
            verify(client).interruptActiveTurn();
            verify(client).reset();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "inject", "turn", "unsubscribe"})
    void resetsProcessOnFailureWithoutPersisting(String phase) throws Exception {
        var client = mock(CodexAppServerClient.class);
        var store = mock(ConversationStore.class);
        when(client.startThread(true, "m")).thenReturn("t");
        when(client.runTurn(anyString(), anyString(), anyString())).thenReturn("Answer");
        var failure = new CodexException(CodexFailure.PROTOCOL, "test");
        switch (phase) {
            case "start" -> when(client.startThread(true, "m")).thenThrow(failure);
            case "inject" -> doThrow(failure).when(client).injectHistory(eq("t"), anyList());
            case "turn" -> when(client.runTurn(anyString(), anyString(), anyString())).thenThrow(failure);
            default -> doThrow(failure).when(client).unsubscribeThread("t");
        }
        assertThatThrownBy(() -> new ConversationService(client, store).complete("req", "m", MESSAGES))
                .isSameAs(failure);
        verify(client).reset();
        verifyNoInteractions(store);
    }

    @Test
    void sharesBusySlotWithOldApiAndSettingsDuringCompletion() throws Exception {
        var client = mock(CodexAppServerClient.class);
        var conversations = mock(ConversationService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(conversations.complete("req", "m", MESSAGES)).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return "done";
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var chat = new ChatService(client, conversations, Duration.ofSeconds(2), Duration.ofMillis(20), executor);
            var completion = caller.submit(() -> chat.complete("req", "m", MESSAGES));
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertBusy(() -> chat.chat("old", "hello"));
                assertBusy(() -> chat.getSettings("settings", "dialog"));
                assertBusy(() -> chat.complete("new", "m", MESSAGES));
            } finally {
                release.countDown();
            }
            assertThat(completion.get(1, TimeUnit.SECONDS)).isEqualTo("done");
        }
    }
}
