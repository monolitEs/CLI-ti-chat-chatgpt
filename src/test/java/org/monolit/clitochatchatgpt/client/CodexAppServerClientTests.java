package org.monolit.clitochatchatgpt.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import tools.jackson.databind.json.JsonMapper;

class CodexAppServerClientTests {

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
