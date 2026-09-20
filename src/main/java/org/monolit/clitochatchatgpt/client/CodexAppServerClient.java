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
import lombok.extern.slf4j.Slf4j;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.model.records.ActiveTurn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class CodexAppServerClient {

    private final ObjectMapper json;
    private final ProcessStarter processStarter;
    private final AtomicLong requestIds = new AtomicLong();

    private volatile Process process;
    private volatile BufferedReader stdout;
    private volatile BufferedWriter stdin;
    private volatile ActiveTurn activeTurn;

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

    public String chat(String requestId, String message) {
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
            var turnResponse = readResponse(turnRequestId);
            var turnId = requiredText(turnResponse, "/result/turn/id");
            activeTurn = new ActiveTurn(threadId, turnId);
            log.info("Codex turn started requestId={} threadId={} turnId={}",
                    requestId, threadId, turnId);
            var answer = readTurn(threadId);
            log.info("Codex turn completed requestId={} threadId={} turnId={}",
                    requestId, threadId, turnId);
            return answer;
        } catch (IOException | RuntimeException exception) {
            reset();
            log.warn("Codex turn failed requestId={} code={}", requestId,
                    exception instanceof CodexException codexException
                            ? codexException.kind() : CodexFailure.UNAVAILABLE);
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException(CodexFailure.UNAVAILABLE,
                    "Codex App Server communication failed", exception);
        } finally {
            activeTurn = null;
        }
    }

    public void interruptActiveTurn() {
        var turn = activeTurn;
        if (turn == null) {
            reset();
            return;
        }
        try {
            send(Map.of(
                    "method", "turn/interrupt",
                    "id", nextId(),
                    "params", Map.of("threadId", turn.threadId(), "turnId", turn.turnId())));
        } catch (IOException | RuntimeException exception) {
            reset();
        }
    }

    private synchronized void ensureStarted() {
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

            var accountId = nextId();
            send(Map.of(
                    "method", "account/read",
                    "id", accountId,
                    "params", Map.of("refreshToken", false)));
            var account = readResponse(accountId).path("result");
            if (account.path("requiresOpenaiAuth").asBoolean()
                    && (account.path("account").isMissingNode() || account.path("account").isNull())) {
                throw new CodexException(CodexFailure.NOT_AUTHENTICATED,
                        "Codex is not authenticated");
            }
        } catch (IOException | RuntimeException exception) {
            reset();
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException(CodexFailure.UNAVAILABLE,
                    "Codex App Server failed to start", exception);
        }
    }

    private String readTurn(String threadId) throws IOException {
        String answer = null;
        while (true) {
            var message = readMessage();
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
                    throw new CodexException(CodexFailure.TURN_FAILED,
                            "Codex turn did not complete successfully");
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
                throw new CodexException(CodexFailure.UNAVAILABLE,
                        "Codex App Server rejected a request");
            }
            return message;
        }
    }

    private JsonNode readMessage() throws IOException {
        var reader = stdout;
        if (reader == null) {
            throw new CodexException(CodexFailure.UNAVAILABLE, "Codex App Server is not running");
        }
        var line = reader.readLine();
        if (line == null) {
            throw new CodexException(CodexFailure.UNAVAILABLE, "Codex App Server closed stdout");
        }
        try {
            return json.readTree(line);
        } catch (RuntimeException exception) {
            throw new CodexException(CodexFailure.PROTOCOL,
                    "Codex App Server returned malformed JSONL", exception);
        }
    }

    private synchronized void send(Object message) throws IOException {
        var writer = stdin;
        if (writer == null) {
            throw new IOException("Codex App Server stdin is closed");
        }
        writer.write(json.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private static String requiredText(JsonNode node, String pointer) {
        var value = node.at(pointer);
        var text = value.stringValueOpt().orElse(null);
        if (text == null || text.isBlank()) {
            throw new CodexException(CodexFailure.PROTOCOL,
                    "Codex App Server response is missing " + pointer);
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

    public synchronized void reset() {
        if (process != null) {
            process.destroyForcibly();
        }
        process = null;
        stdout = null;
        stdin = null;
        activeTurn = null;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

}
