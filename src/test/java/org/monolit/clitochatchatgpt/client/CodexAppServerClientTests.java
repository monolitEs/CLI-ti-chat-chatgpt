package org.monolit.clitochatchatgpt.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CodexAppServerClientTests {

    @Test
    void performsHandshakeAndReturnsCompletedAgentMessage() {
        var stdout = """
                {"id":1,"result":{"userAgent":"test"}}
                {"id":2,"result":{"thread":{"id":"thr_1"}}}
                {"id":3,"result":{"turn":{"id":"turn_1","status":"inProgress","items":[]}}}
                {"method":"item/completed","params":{"threadId":"thr_1","turnId":"turn_1","item":{"type":"agentMessage","id":"item_1","text":"Hello back"}}}
                {"method":"turn/completed","params":{"threadId":"thr_1","turn":{"id":"turn_1","status":"completed","items":[]}}}
                """;
        var process = new FakeProcess(stdout);
        var client = new CodexAppServerClient(JsonMapper.builder().build(), () -> process);

        assertThat(client.chat("Hello")).isEqualTo("Hello back");

        var requests = process.stdin.toString(StandardCharsets.UTF_8);
        assertThat(requests).contains("\"method\":\"initialize\"");
        assertThat(requests).contains("\"method\":\"initialized\"");
        assertThat(requests).contains("\"method\":\"thread/start\"");
        assertThat(requests).contains("\"ephemeral\":true");
        assertThat(requests).contains("\"method\":\"turn/start\"");
        assertThat(requests).contains("\"text\":\"Hello\"");
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private boolean alive = true;

        private FakeProcess(String stdout) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
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
}
