package org.monolit.clitochatchatgpt.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class CodexAppServerClient {

    private final ObjectMapper json;
    private final ProcessStarter processStarter;
    private final AtomicLong requestIds = new AtomicLong();

    private Process process;
    private BufferedReader stdout;
    private BufferedWriter stdin;

    @Autowired
    public CodexAppServerClient(ObjectMapper json, @Value("${codex.command:codex}") String command) {
        this(json, () -> new ProcessBuilder(command, "app-server", "--strict-config")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start());
    }

    CodexAppServerClient(ObjectMapper json, ProcessStarter processStarter) {
        this.json = json;
        this.processStarter = processStarter;
    }

    public synchronized String chat(String message) {
        ensureStarted();
        try {
            var threadRequestId = nextId();
            send(Map.of(
                    "method", "thread/start",
                    "id", threadRequestId,
                    "params", Map.of(
                            "serviceName", "cli_to_chat_chatgpt",
                            "ephemeral", true)));
            var threadResponse = readResponse(threadRequestId);
            var threadId = requiredText(threadResponse, "/result/thread/id");

            var turnRequestId = nextId();
            send(Map.of(
                    "method", "turn/start",
                    "id", turnRequestId,
                    "params", Map.of(
                            "threadId", threadId,
                            "input", List.of(Map.of("type", "text", "text", message)))));
            return readTurn(threadId, turnRequestId);
        } catch (IOException | RuntimeException exception) {
            close();
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException("Codex App Server communication failed", exception);
        }
    }

    private void ensureStarted() {
        if (process != null && process.isAlive()) {
            return;
        }
        close();
        try {
            process = processStarter.start();
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            var initializeId = nextId();
            send(Map.of(
                    "method", "initialize",
                    "id", initializeId,
                    "params", Map.of("clientInfo", Map.of(
                            "name", "cli_to_chat_chatgpt",
                            "title", "CLI to Chat ChatGPT",
                            "version", "0.0.1"))));
            readResponse(initializeId);
            send(Map.of("method", "initialized", "params", Map.of()));
        } catch (IOException | RuntimeException exception) {
            close();
            throw new CodexException("Codex App Server failed to start", exception);
        }
    }

    private String readTurn(String threadId, long turnRequestId) throws IOException {
        String answer = null;
        while (true) {
            var message = readMessage();
            if (message.path("id").canConvertToLong()
                    && message.path("id").asLong() == turnRequestId
                    && !message.path("error").isMissingNode()) {
                throw new CodexException("Codex rejected turn/start");
            }

            var method = text(message.path("method"));
            var params = message.path("params");
            if (!threadId.equals(text(params.path("threadId")))) {
                continue;
            }
            if ("item/completed".equals(method)
                    && "agentMessage".equals(text(params.path("item").path("type")))) {
                answer = text(params.path("item").path("text"));
            }
            if ("turn/completed".equals(method)) {
                var status = text(params.path("turn").path("status"));
                if (!"completed".equals(status) || answer == null) {
                    throw new CodexException("Codex turn did not complete successfully");
                }
                return answer;
            }
        }
    }

    private JsonNode readResponse(long expectedId) throws IOException {
        while (true) {
            var message = readMessage();
            if (!message.path("id").canConvertToLong() || message.path("id").asLong() != expectedId) {
                continue;
            }
            if (!message.path("error").isMissingNode()) {
                throw new CodexException("Codex App Server rejected a request");
            }
            return message;
        }
    }

    private JsonNode readMessage() throws IOException {
        var line = stdout.readLine();
        if (line == null) {
            throw new CodexException("Codex App Server closed stdout");
        }
        return json.readTree(line);
    }

    private void send(Object message) throws IOException {
        stdin.write(json.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    private static String requiredText(JsonNode node, String pointer) {
        var value = node.at(pointer);
        var text = value.stringValueOpt().orElse(null);
        if (text == null || text.isBlank()) {
            throw new CodexException("Codex App Server response is missing " + pointer);
        }
        return text;
    }

    private static String text(JsonNode node) {
        return node.stringValueOpt().orElse("");
    }

    private long nextId() {
        return requestIds.incrementAndGet();
    }

    @PreDestroy
    public synchronized void close() {
        if (process != null) {
            process.destroy();
        }
        process = null;
        stdout = null;
        stdin = null;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    public static final class CodexException extends RuntimeException {
        CodexException(String message) {
            super(message);
        }

        CodexException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
