package org.monolit.clitochatchatgpt.client;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.monolit.clitochatchatgpt.model.dto.ModelSettingsResponse;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.model.records.ActiveTurn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class CodexAppServerClient {

    private final ObjectMapper json;
    private final ProcessStarter processStarter;
    private final AtomicLong requestIds = new AtomicLong();

    private static final String METHOD = "method";

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

    public String runTurn(String requestId, String message, String threadId) {
        try {
            var turnRequestId = nextId();
            send(Map.of(
                    METHOD, "turn/start",
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

    public String resumeThread(String threadId) throws IOException {
        var threadRequestId = nextId();
        send(Map.of(METHOD, "thread/resume", "id", threadRequestId,
                "params", Map.of("threadId", threadId)));
        var resumedId = requiredText(readResponse(threadRequestId), "/result/thread/id");
        if (!threadId.equals(resumedId)) {
            throw new CodexException(CodexFailure.PROTOCOL, "Resumed thread ID does not match");
        }
        return resumedId;
    }

    private static String nullableText(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        return requiredText(node, "");
    }

    public String startThread(boolean ephemeral) throws IOException {
        var threadRequestId = nextId();
        send(Map.of(
                METHOD, "thread/start",
                "id", threadRequestId,
                "params", Map.of(
                        "serviceName", "cli_to_chat_chatgpt",
                        "ephemeral", ephemeral)));
        var threadResponse = readResponse(threadRequestId);
        return requiredText(threadResponse, "/result/thread/id");
    }

    public ModelSettingsResponse readSettings(String threadId) throws IOException {
        var thread = request("thread/read", Map.of("threadId", threadId, "includeTurns", false)).path("thread");
        if (!threadId.equals(requiredText(thread, "/id"))) {
            throw new CodexException(CodexFailure.PROTOCOL, "Read thread ID does not match");
        }
        return new ModelSettingsResponse(null, null, nullableText(thread.path("model")),
                nullableText(thread.path("reasoningEffort")));
    }

    public ModelSettingsResponse updateSettings(String threadId, String model, String effort) throws IOException {
        // Pinned CLI 0.159.3 rejoins loaded threads without applying resume overrides.
        request("thread/unsubscribe", Map.of("threadId", threadId));
        var resumed = request("thread/resume", Map.of("threadId", threadId, "model", model,
                "config", Map.of("model_reasoning_effort", effort)));
        if (!threadId.equals(requiredText(resumed, "/thread/id"))) {
            throw new CodexException(CodexFailure.PROTOCOL, "Resumed thread ID does not match");
        }
        var settings = readSettings(threadId);
        if (!model.equals(settings.getModel()) || !effort.equals(settings.getReasoningEffort())) {
            throw new CodexException(CodexFailure.PROTOCOL, "Codex did not apply requested settings");
        }
        return settings;
    }

    public boolean supportsSettings(String model, String effort) throws IOException {
        String cursor = null;
        var seenCursors = new HashSet<String>();
        do {
            var params = cursor == null ? Map.<String, Object>of("limit", 100)
                    : Map.<String, Object>of("limit", 100, "cursor", cursor);
            var result = request("model/list", params);
            var models = result.path("data");
            if (!models.isArray()) {
                throw new CodexException(CodexFailure.PROTOCOL, "Model catalog is not an array");
            }
            for (var entry : models) {
                if (!model.equals(requiredText(entry, "/model"))) {
                    continue;
                }
                var efforts = entry.path("supportedReasoningEfforts");
                if (!efforts.isArray()) {
                    throw new CodexException(CodexFailure.PROTOCOL, "Model efforts are not an array");
                }
                for (var supported : efforts) {
                    if (effort.equals(requiredText(supported, "/reasoningEffort"))) {
                        return true;
                    }
                }
            }
            cursor = nullableText(result.path("nextCursor"));
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new CodexException(CodexFailure.PROTOCOL, "Model catalog cursor repeated");
            }
        } while (cursor != null);
        return false;
    }

    private JsonNode request(String method, Map<String, Object> params) throws IOException {
        var id = nextId();
        send(Map.of(METHOD, method, "id", id, "params", params));
        return readResponse(id).path("result");
    }

    public void interruptActiveTurn() {
        var turn = activeTurn;
        if (turn == null) {
            reset();
            return;
        }
        try {
            send(Map.of(
                    METHOD, "turn/interrupt",
                    "id", nextId(),
                    "params", Map.of("threadId", turn.threadId(), "turnId", turn.turnId())));
        } catch (IOException | RuntimeException exception) {
            reset();
        }
    }

    public synchronized void ensureStarted() {
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
                    METHOD, "initialize",
                    "id", initializeId,
                    "params", Map.of("clientInfo", Map.of(
                            "name", "cli_to_chat_chatgpt",
                            "title", "CLI to Chat ChatGPT",
                            "version", "0.0.1"))));
            readResponse(initializeId);
            send(Map.of(METHOD, "initialized", "params", Map.of()));

            var accountId = nextId();
            send(Map.of(
                    METHOD, "account/read",
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
            var method = text(message.path(METHOD));
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
