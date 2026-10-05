package org.monolit.clitochatchatgpt.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import tools.jackson.databind.json.JsonMapper;

class CodexAppServerClientTests {

    @TempDir
    Path directory;

    @Test
    void persistsSeparateThreadsAndResumesSameConversation() throws IOException {
        var stdout = protocolPrefix() + completed("thr_1", "first")
                + threadTurn(5, "thr_1") + completed("thr_1", "continued")
                + threadTurn(7, "thr_2") + completed("thr_2", "separate");
        var process = new FakeProcess(stdout);
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.resolve("conversations.json"));
        var client = new CodexAppServerClient(mapper, () -> process, store);

        assertThat(client.chat("req-1", "first", "a")).isEqualTo("first");
        assertThat(store.find("a")).isEqualTo("thr_1");
        assertThat(client.chat("req-2", "continue", "a")).isEqualTo("continued");
        assertThat(client.chat("req-3", "separate", "b")).isEqualTo("separate");
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
    void resumesPersistedThreadAfterClientAndAppServerRestart() throws IOException {
        var mapper = JsonMapper.builder().build();
        var file = directory.resolve("conversations.json");
        var firstProcess = new FakeProcess(protocolPrefix() + completed("thr_1", "saved"));
        var first = new CodexAppServerClient(mapper, () -> firstProcess, new ConversationStore(mapper, file));
        first.chat("req-1", "save", "a");
        first.reset();
        var nextProcess = new FakeProcess(protocolPrefix() + completed("thr_1", "remembered"));
        var next = new CodexAppServerClient(mapper, () -> nextProcess, new ConversationStore(mapper, file));
        assertThat(next.chat("req-2", "continue", "a")).isEqualTo("remembered");
        assertThat(nextProcess.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"");
    }

    @Test
    void resumesAfterResetWithoutLosingMapping() throws IOException {
        var mapper = JsonMapper.builder().build();
        var processes = new ArrayDeque<>(List.of(
                new FakeProcess(protocolPrefix() + completed("thr_1", "saved")),
                new FakeProcess(handshake(5) + threadTurn(7, "thr_1") + completed("thr_1", "remembered"))));
        var secondProcess = processes.getLast();
        var client = new CodexAppServerClient(mapper, processes::remove,
                new ConversationStore(mapper, directory.resolve("conversations.json")));
        client.chat("req-1", "save", "a");
        client.reset();
        assertThat(client.chat("req-2", "continue", "a")).isEqualTo("remembered");
        assertThat(secondProcess.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"");
    }

    @Test
    void failedResumeDoesNotStartNewThreadOrSendTurn() throws IOException {
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.resolve("conversations.json"));
        store.save("a", "thr_missing");
        var process = new FakeProcess(handshake(1) + "{\"id\":3,\"error\":{\"code\":-32600,\"message\":\"missing\"}}\n");
        var client = new CodexAppServerClient(mapper, () -> process, store);
        assertThatThrownBy(() -> client.chat("req-1", "continue", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.UNAVAILABLE));
        assertThat(process.requests()).contains("\"method\":\"thread/resume\"")
                .doesNotContain("\"method\":\"thread/start\"", "\"method\":\"turn/start\"");
        assertThat(store.find("a")).isEqualTo("thr_missing");
    }

    @Test
    void mismatchedResumeIdDoesNotSendTurn() throws IOException {
        var mapper = JsonMapper.builder().build();
        var store = new ConversationStore(mapper, directory.resolve("conversations.json"));
        store.save("a", "thr_expected");
        var process = new FakeProcess(protocolPrefix());
        var client = new CodexAppServerClient(mapper, () -> process, store);
        assertThatThrownBy(() -> client.chat("req-1", "continue", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
        assertThat(process.requests()).doesNotContain("\"method\":\"turn/start\"");
    }

    @Test
    void storageWriteFailurePreventsTurnFromStarting() throws IOException {
        var mapper = JsonMapper.builder().build();
        var process = new FakeProcess(protocolPrefix());
        var store = mock(ConversationStore.class);
        doThrow(new IOException("Disk full")).when(store).save("a", "thr_1");
        var client = new CodexAppServerClient(mapper, () -> process, store);
        assertThatThrownBy(() -> client.chat("req-1", "save", "a"))
                .isInstanceOfSatisfying(CodexException.class,
                        e -> assertThat(e.kind()).isEqualTo(CodexFailure.UNAVAILABLE));
        assertThat(process.requests()).contains("\"method\":\"thread/start\"")
                .doesNotContain("\"method\":\"turn/start\"");
    }

    @Test
    void savesMappingBeforeTurnStart() throws IOException {
        var mapper = JsonMapper.builder().build();
        var process = new FakeProcess(protocolPrefix() + completed("thr_1", "answer"));
        var store = mock(ConversationStore.class);
        doAnswer(invocation -> {
            assertThat(process.requests()).contains("\"method\":\"thread/start\"")
                    .doesNotContain("\"method\":\"turn/start\"");
            return null;
        }).when(store).save("a", "thr_1");
        var client = new CodexAppServerClient(mapper, () -> process, store);
        assertThat(client.chat("req-1", "Hello", "a")).isEqualTo("answer");
    }

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
    void performsHandshakeAndReturnsCompletedAgentMessage() {
        var stdout = protocolPrefix() + """
                {"method":"item/completed","params":{"threadId":"thr_1","turnId":"turn_1","item":{"type":"agentMessage","id":"item_1","text":"Hello back"}}}
                {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"completed","items":[]}}}
                """;
        var process = new FakeProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThat(client.chat("req-1", "Hello")).isEqualTo("Hello back");

        var requests = process.requests();
        assertThat(requests).contains("\"method\":\"initialize\"");
        assertThat(requests).contains("\"method\":\"initialized\"");
        assertThat(requests).contains("\"method\":\"account/read\"");
        assertThat(requests).contains("\"method\":\"thread/start\"");
        assertThat(requests).contains("\"ephemeral\":true");
        assertThat(requests).contains("\"method\":\"turn/start\"");
        assertThat(requests).contains("\"text\":\"Hello\"");
    }

    @Test
    void classifiesMalformedJsonlAsProtocolFailure() {
        var process = new FakeProcess(protocolPrefix() + "not-json\n");
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> client.chat("req-1", "Hello"))
                .isInstanceOfSatisfying(CodexException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(CodexFailure.PROTOCOL));
    }

    @Test
    void classifiesFailedTurn() {
        var stdout = protocolPrefix() + """
                {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"failed","items":[]}}}
                """;
        var process = new FakeProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> client.chat("req-1", "Hello"))
                .isInstanceOfSatisfying(CodexException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(CodexFailure.TURN_FAILED));
    }

    @Test
    void classifiesMissingChatGptLogin() {
        var stdout = """
                {"id":1,"result":{"userAgent":"test"}}
                {"id":2,"result":{"account":null,"requiresOpenaiAuth":true}}
                """;
        var process = new FakeProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> client.chat("req-1", "Hello"))
                .isInstanceOfSatisfying(CodexException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(CodexFailure.NOT_AUTHENTICATED));
    }

    @Test
    void classifiesUnexpectedProcessExitAsUnavailable() {
        var stdout = """
                {"id":1,"result":{"userAgent":"test"}}
                {"id":2,"result":{"account":{"type":"chatgpt"},"requiresOpenaiAuth":true}}
                """;
        var process = new FakeProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> client.chat("req-1", "Hello"))
                .isInstanceOfSatisfying(CodexException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(CodexFailure.UNAVAILABLE));
    }

    @Test
    void sendsTurnInterruptWithActiveIds() throws Exception {
        var process = new StreamingProcess();
        process.respond(protocolPrefix());
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result = executor.submit(() -> client.chat("req-1", "Hello"));
            awaitRequest(process, "\"method\":\"turn/start\"");

            client.interruptActiveTurn();

            awaitRequest(process, "\"method\":\"turn/interrupt\"");
            assertThat(process.requests())
                    .contains("\"threadId\":\"thr_1\"")
                    .contains("\"turnId\":\"turn_1\"");
            process.respond("""
                    {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"interrupted","items":[]}}}
                    """);
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CodexException.class);
        } finally {
            executor.shutdownNow();
            process.destroy();
        }
    }

    private static String protocolPrefix() {
        return """
                {"id":1,"result":{"userAgent":"test"}}
                {"id":2,"result":{"account":{"type":"chatgpt"},"requiresOpenaiAuth":true}}
                {"id":3,"result":{"thread":{"id":"thr_1"}}}
                {"id":4,"result":{"turn":{"id":"turn_1","status":"inProgress","items":[]}}}
                """;
    }

    private static void awaitRequest(StreamingProcess process, String fragment) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!process.requests().contains(fragment) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(process.requests()).contains(fragment);
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private boolean alive = true;

        private FakeProcess(String stdout) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        }

        private String requests() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class StreamingProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream responses;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean alive = true;

        private StreamingProcess() throws Exception {
            responses = new PipedOutputStream(stdout);
        }

        private void respond(String response) throws Exception {
            responses.write(response.getBytes(StandardCharsets.UTF_8));
            responses.flush();
        }

        private String requests() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            try {
                responses.close();
            } catch (Exception ignored) {
                // Test transport is already closed.
            }
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
