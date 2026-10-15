package org.monolit.clitochatchatgpt.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.utils.FakeCodexProcess;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.monolit.clitochatchatgpt.utils.FakeCodexProcess.protocolPrefix;

class CodexAppServerClientTests {

    private static String chat(CodexAppServerClient client, String requestId, String message) throws IOException {
        client.ensureStarted();
        var threadId = client.startThread(true);
        return client.runTurn(requestId, message, threadId);
    }

    @Test
    void performsHandshakeAndReturnsCompletedAgentMessage() throws IOException {
        var stdout = protocolPrefix() + """
                {"method":"item/completed","params":{"threadId":"thr_1","turnId":"turn_1","item":{"type":"agentMessage","id":"item_1","text":"Hello back"}}}
                {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"completed","items":[]}}}
                """;
        var process = new FakeCodexProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThat(chat(client, "req-1", "Hello")).isEqualTo("Hello back");

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
        var process = new FakeCodexProcess(protocolPrefix() + "not-json\n");
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> chat(client, "req-1", "Hello"))
                .isInstanceOfSatisfying(CodexException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(CodexFailure.PROTOCOL));
    }

    @Test
    void classifiesFailedTurn() {
        var stdout = protocolPrefix() + """
                {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"failed","items":[]}}}
                """;
        var process = new FakeCodexProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> chat(client, "req-1", "Hello"))
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
        var process = new FakeCodexProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> chat(client, "req-1", "Hello"))
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
        var process = new FakeCodexProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThatThrownBy(() -> chat(client, "req-1", "Hello"))
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
            var result = executor.submit(() -> chat(client, "req-1", "Hello"));
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

    private static void awaitRequest(StreamingProcess process, String fragment) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!process.requests().contains(fragment) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(process.requests()).contains(fragment);
    }

    private static String settingsPrefix() {
        return """
                {"id":1,"result":{}}
                {"id":2,"result":{"requiresOpenaiAuth":false}}
                """;
    }

    @Test
    void readsNullableMetadataWithoutResumingOrSendingTurn() throws IOException {
        var process = new FakeCodexProcess(settingsPrefix() + """
                {"id":3,"result":{"thread":{"id":"t","model":null,"reasoningEffort":null}}}
                """);
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        var settings = client.readSettings("t");
        assertThat(settings.getModel()).isNull();
        assertThat(settings.getReasoningEffort()).isNull();
        assertThat(process.requests()).contains("\"includeTurns\":false")
                .doesNotContain("thread/resume", "turn/start", "thread/start");
    }

    @Test
    void validatesPairAcrossCatalogPagesWithoutChangingThread() throws IOException {
        var process = new FakeCodexProcess(settingsPrefix() + """
                {"id":3,"result":{"data":[{"model":"other"}],"nextCursor":"next"}}
                {"id":4,"result":{"data":[{"model":"m","supportedReasoningEfforts":[{"reasoningEffort":"future-effort"}]}],"nextCursor":null}}
                """);
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        assertThat(client.supportsSettings("m", "future-effort")).isTrue();
        assertThat(process.requests()).contains("\"cursor\":\"next\"")
                .doesNotContain("thread/resume", "thread/unsubscribe", "turn/start");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unsupported"})
    void rejectsUnadvertisedPair(String model) throws IOException {
        var process = new FakeCodexProcess(settingsPrefix() + """
                {"id":3,"result":{"data":[{"model":"unsupported","supportedReasoningEfforts":[{"reasoningEffort":"low"}]}],"nextCursor":null}}
                """);
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        assertThat(client.supportsSettings(model, "high")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":null,\"nextCursor\":null}",
            "{\"data\":[{\"model\":\"m\",\"supportedReasoningEfforts\":null}],\"nextCursor\":null}",
            "{\"data\":[{\"model\":\"m\",\"supportedReasoningEfforts\":[{}]}],\"nextCursor\":null}",
            "{\"data\":[],\"nextCursor\":123}"})
    void classifiesMalformedCatalogAsProtocolFailure(String result) {
        var process = new FakeCodexProcess(settingsPrefix() + "{\"id\":3,\"result\":" + result + "}\n");
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        assertThatThrownBy(() -> client.supportsSettings("m", "high"))
                .isInstanceOfSatisfying(CodexException.class, e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
    }

    @Test
    void detectsRepeatedCatalogCursor() {
        var page = "{\"id\":%d,\"result\":{\"data\":[],\"nextCursor\":\"same\"}}\n";
        var process = new FakeCodexProcess(settingsPrefix() + page.formatted(3) + page.formatted(4));
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        assertThatThrownBy(() -> client.supportsSettings("m", "high"))
                .isInstanceOfSatisfying(CodexException.class, e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
    }

    @Test
    void unsubscribesBeforeResumeAndConfirmsSettingsThroughRead() throws IOException {
        var process = new FakeCodexProcess(settingsPrefix() + """
                {"id":3,"result":{"status":"unsubscribed"}}
                {"id":4,"result":{"thread":{"id":"t"},"model":"m","reasoningEffort":"high"}}
                {"id":5,"result":{"thread":{"id":"t","model":"m","reasoningEffort":"high"}}}
                """);
        var mapper = JsonMapper.builder().build();
        var client = process.client(mapper);
        client.ensureStarted();
        var settings = client.updateSettings("t", "m", "high");
        assertThat(settings.getModel()).isEqualTo("m");
        assertThat(settings.getReasoningEffort()).isEqualTo("high");
        var requests = process.requests().lines().map(mapper::readTree).toList();
        assertThat(requests.stream().map(n -> n.path("method").asString()))
                .containsExactly("initialize", "initialized", "account/read", "thread/unsubscribe", "thread/resume", "thread/read");
        assertThat(requests.get(4).at("/params/config/model_reasoning_effort").asString()).isEqualTo("high");
        assertThat(requests.get(4).at("/params/model").asString()).isEqualTo("m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "t"})
    void rejectsWrongThreadOrUnappliedSettings(String returnedId) {
        var process = new FakeCodexProcess(settingsPrefix() + """
                {"id":3,"result":{"status":"notLoaded"}}
                {"id":4,"result":{"thread":{"id":"%s"}}}
                {"id":5,"result":{"thread":{"id":"t","model":"old","reasoningEffort":"low"}}}
                """.formatted(returnedId));
        var client = process.client(JsonMapper.builder().build());
        client.ensureStarted();
        assertThatThrownBy(() -> client.updateSettings("t", "m", "high"))
                .isInstanceOfSatisfying(CodexException.class, e -> assertThat(e.kind()).isEqualTo(CodexFailure.PROTOCOL));
        assertThat(process.requests()).doesNotContain("turn/start", "thread/start");
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
