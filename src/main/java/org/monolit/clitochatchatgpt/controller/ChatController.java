package org.monolit.clitochatchatgpt.controller;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.model.records.ChatError;
import org.monolit.clitochatchatgpt.model.records.ChatRequest;
import org.monolit.clitochatchatgpt.model.records.ChatResponse;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@Slf4j
public class ChatController {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody(required = false) ChatRequest request) {
        var requestId = UUID.randomUUID().toString();
        var started = System.nanoTime();
        if (request == null || request.message() == null || request.message().isBlank()) {
            log.warn("Chat request rejected requestId={} code=MESSAGE_REQUIRED", requestId);
            return response(HttpStatus.BAD_REQUEST, requestId,
                    new ChatError(requestId, "MESSAGE_REQUIRED"));
        }

        var idNode = request.conversationId();
        String conversationId = null;
        if (idNode != null && !idNode.isNull()) {
            conversationId = idNode.stringValueOpt().orElse(null);
            if (conversationId == null || conversationId.isBlank() || conversationId.length() > 256) {
                return error(HttpStatus.BAD_REQUEST, requestId, "CONVERSATION_ID_INVALID", started);
            }
        }

        try {
            var answer = chatService.chat(requestId, request.message(), conversationId);
            log.info("Chat request completed requestId={} durationMs={}",
                    requestId, elapsedMillis(started));
            return response(HttpStatus.OK, requestId, new ChatResponse(requestId, answer));
        } catch (ChatException exception) {
            return switch (exception.failure()) {
                case BUSY -> error(HttpStatus.CONFLICT, requestId, "CHAT_BUSY", started);
                case BAD_GATEWAY -> error(HttpStatus.BAD_GATEWAY, requestId,
                        "CODEX_BAD_GATEWAY", started);
                case NOT_AUTHENTICATED -> error(HttpStatus.SERVICE_UNAVAILABLE, requestId,
                        "CODEX_NOT_AUTHENTICATED", started);
                case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, requestId,
                        "CODEX_UNAVAILABLE", started);
                case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, requestId,
                        "CODEX_TIMEOUT", started);
            };
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String requestId,
            String code, long started) {
        log.warn("Chat request failed requestId={} code={} durationMs={}",
                requestId, code, elapsedMillis(started));
        return response(status, requestId, new ChatError(requestId, code));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static ResponseEntity<Object> response(HttpStatus status, String requestId, Object body) {
        return ResponseEntity.status(status)
                .header(REQUEST_ID_HEADER, requestId)
                .body(body);
    }

}
