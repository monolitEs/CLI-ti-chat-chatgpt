package org.monolit.clitochatchatgpt.controller;

import java.util.UUID;

import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody(required = false) ChatRequest request) {
        var requestId = UUID.randomUUID().toString();
        if (request == null || request.message() == null || request.message().isBlank()) {
            return response(HttpStatus.BAD_REQUEST, requestId,
                    new ChatError(requestId, "MESSAGE_REQUIRED"));
        }

        try {
            var answer = chatService.chat(request.message());
            return response(HttpStatus.OK, requestId, new ChatResponse(requestId, answer));
        } catch (ChatService.ChatUnavailableException exception) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, requestId,
                    new ChatError(requestId, "CODEX_UNAVAILABLE"));
        }
    }

    private static ResponseEntity<Object> response(HttpStatus status, String requestId, Object body) {
        return ResponseEntity.status(status)
                .header(REQUEST_ID_HEADER, requestId)
                .body(body);
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String requestId, String answer) {
    }

    public record ChatError(String requestId, String code) {
    }
}
