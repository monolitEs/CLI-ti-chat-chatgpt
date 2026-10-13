package org.monolit.clitochatchatgpt.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.persistence.ConversationStore;
import org.monolit.clitochatchatgpt.utils.FakeCodexProcess;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.monolit.clitochatchatgpt.utils.FakeCodexProcess.protocolPrefix;

class ConversationServiceTests {

    @TempDir
    Path directory;

    private static String handshake(int id) {
        return "{\"id\":" + id + ",\"result\":{}}\n"
                + "{\"id\":" + (id + 1) + ",\"result\":{\"account\":{\"type\":\"chatgpt\"}}}\n";
    }

    private static String threadTurn(int id, String thread) {
        return """
                {"id":%d,"result":{"thread":{"id":"%s"}}}
                {"id":%d,"result":{"turn":{"id":"turn"}}}
                """.formatted(id, thread, id + 1);
    }

    private static String completed(String thread, String answer) {
        return """
                {"method":"item/completed","params":{"threadId":"%s","item":{"type":"agentMessage","text":"%s"}}}
                {"method":"turn/completed","params":{"threadId":"%s","turn":{"status":"completed"}}}
                """.formatted(thread, answer, thread);
    }

    @Test
    void persistsSeparateThreadsAndResumesSameConversation() throws IOException {
        var stdout = protocolPrefix() + completed("thr_1", "first")
                + threadTurn(5, "thr_1") + completed("thr_1", "continued")
                + threadTurn(7, "thr_2") + completed("thr_2", "separate");
        var process = new FakeCodexProcess(stdout);
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.toString());
        var client = process.client(mapper);
        var service = new ConversationService(client, store);

        assertThat(service.chat("req-1", "first", "a")).isEqualTo("first");
        assertThat(store.find("a")).isEqualTo("thr_1");
        assertThat(service.chat("req-2", "continue", "a")).isEqualTo("continued");
        assertThat(service.chat("req-3", "separate", "b")).isEqualTo("separate");
        assertThat(store.find("b")).isEqualTo("thr_2");
        var requests = process.requests().lines().map(mapper::readTree).toList();
        var methods = requests.stream().map(n -> n.path("method").asString()).toList();
        assertThat(methods).containsExactly("initialize", "initialized", "account/read",
                "thread/start", "turn/start", "thread/resume", "turn/start", "thread/start", "turn/start");
        assertThat(requests.get(3).at("/params/ephemeral").asBoolean()).isFalse();
        assertThat(requests.get(5).at("/params/threadId").asString()).isEqualTo("thr_1");
        assertThat(requests.get(8).at("/params/threadId").asString()).isEqualTo("thr_2");
    }

    @Test
    void resumesPersistedThreadAfterClientAndAppServerRestart() {
        var mapper = JsonMapper.builder().build();
        var firstProcess = new FakeCodexProcess(protocolPrefix() + completed("thr_1", "saved"));
        var firstClient = firstProcess.client(mapper);
        var first = new ConversationService(firstClient, new ConversationStore(mapper, directory.toString()));
        first.chat("req-1", "save", "a");
        firstClient.reset();
        var nextProcess = new FakeCodexProcess(protocolPrefix() + completed("thr_1", "remembered"));
        var next = new ConversationService(nextProcess.client(mapper), new ConversationStore(mapper, directory.toString()));
        assertThat(next.chat("req-2", "continue", "a")).isEqualTo("remembered");
        assertThat(nextProcess.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"");
    }

    @Test
    void resumesAfterResetWithoutLosingMapping() {
        var mapper = JsonMapper.builder().build();
        var processes = new ArrayDeque<>(List.of(
                new FakeCodexProcess(protocolPrefix() + completed("thr_1", "saved")),
                new FakeCodexProcess(handshake(5) + threadTurn(7, "thr_1") + completed("thr_1", "remembered"))));
        var secondProcess = processes.getLast();
        var client = FakeCodexProcess.client(mapper, processes::remove);
        var service = new ConversationService(client, new ConversationStore(mapper, directory.toString()));
        service.chat("req-1", "save", "a");
        client.reset();
        assertThat(service.chat("req-2", "continue", "a")).isEqualTo("remembered");
        assertThat(secondProcess.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"");
    }

    @Test
    void failedResumeDoesNotStartNewThreadOrSendTurn() throws IOException {
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.toString());
        store.save("a", "thr_missing");
        var process = new FakeCodexProcess(handshake(1) + "{\"id\":3,\"error\":{\"code\":-32600,\"message\":\"missing\"}}\n");
        var client = process.client(mapper);
        var service = new ConversationService(client, store);
        assertThatThrownBy(() -> service.chat("req-1", "continue", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.UNAVAILABLE));
        assertThat(process.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"", "\"method\":\"turn/start\"");
        assertThat(store.find("a")).isEqualTo("thr_missing");
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void mismatchedResumeIdDoesNotSendTurn() throws IOException {
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.toString());
        store.save("a", "thr_expected");
        var process = new FakeCodexProcess(protocolPrefix());
        var client = process.client(mapper);
        var service = new ConversationService(client, store);
        assertThatThrownBy(() -> service.chat("req-1", "continue", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
        assertThat(process.requests()).doesNotContain("\"method\":\"turn/start\"");
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void storageWriteFailurePreventsTurnFromStarting() throws IOException {
        var mapper = JsonMapper.builder().build();
        var process = new FakeCodexProcess(protocolPrefix());
        var store = mock(ConversationStore.class);
        doThrow(new IOException("Disk full")).when(store).save("a", "thr_1");
        var client = process.client(mapper);
        var service = new ConversationService(client, store);
        assertThatThrownBy(() -> service.chat("req-1", "save", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.UNAVAILABLE));
        assertThat(process.requests()).contains("\"method\":\"thread/start\"")
                .doesNotContain("\"method\":\"turn/start\"");
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void savesMappingBeforeTurnStart() throws IOException {
        var mapper = JsonMapper.builder().build();
        var process = new FakeCodexProcess(protocolPrefix() + completed("thr_1", "answer"));
        var store = mock(ConversationStore.class);
        doAnswer(invocation -> {
            assertThat(process.requests()).contains("\"method\":\"thread/start\"")
                    .doesNotContain("\"method\":\"turn/start\"");
            return null;
        }).when(store).save("a", "thr_1");
        var client = process.client(mapper);
        var service = new ConversationService(client, store);
        assertThat(service.chat("req-1", "Hello", "a")).isEqualTo("answer");
    }

    @Test
    void oneShotDoesNotReadOrWriteTheConversationStore() {
        var mapper = JsonMapper.builder().build();
        var process = new FakeCodexProcess(protocolPrefix() + completed("thr_1", "answer"));
        var store = mock(ConversationStore.class);
        var service = new ConversationService(process.client(mapper), store);

        assertThat(service.chat("req-1", "Hello", null)).isEqualTo("answer");

        verifyNoInteractions(store);
        assertThat(process.requests()).contains("\"ephemeral\":true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt", "deleted"})
    void storageReadFailureResetsProcessWithoutStartingThreadOrTurn(String failure) throws IOException {
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.toString());
        store.save("a", "thr_1");
        var file = directory.resolve("chat-service/conversations.json");
        if (failure.equals("corrupt")) {
            Files.writeString(file, "{");
        } else {
            Files.delete(file);
        }
        var process = new FakeCodexProcess(handshake(1));
        var service = new ConversationService(process.client(mapper), store);

        assertThatThrownBy(() -> service.chat("req-1", "continue", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.UNAVAILABLE));

        assertThat(process.requests()).doesNotContain("\"method\":\"thread/start\"",
                "\"method\":\"thread/resume\"", "\"method\":\"turn/start\"");
        assertThat(process.isAlive()).isFalse();
        if (failure.equals("corrupt")) {
            assertThat(Files.readString(file)).isEqualTo("{");
        } else {
            assertThat(file).doesNotExist();
        }
    }

}
