package org.monolit.clitochatchatgpt.controller;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@RestControllerAdvice(assignableTypes = ChatCompletionsController.class)
@NullMarked
public class ChatCompletionsExceptionHandler extends ResponseEntityExceptionHandler {

    private static ResponseEntity<Object> error(HttpStatusCode status, String code, WebRequest request) {
        request.setAttribute(ChatRequestFilter.ERROR_CODE_ATTRIBUTE, code, RequestAttributes.SCOPE_REQUEST);
        return ResponseEntity.status(status).body(Map.of("error", Map.of(
                "message", code, "type", status.is4xxClientError() ? "invalid_request_error" : "server_error",
                "code", code)));
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Object> handleChatFailure(ChatException exception, WebRequest request) {
        return switch (exception.failure()) {
            case BUSY -> error(HttpStatus.CONFLICT, "CHAT_BUSY", request);
            case BAD_GATEWAY -> error(HttpStatus.BAD_GATEWAY, "CODEX_BAD_GATEWAY", request);
            case NOT_AUTHENTICATED -> error(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_NOT_AUTHENTICATED", request);
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_UNAVAILABLE", request);
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "CODEX_TIMEOUT", request);
            case CONVERSATION_NOT_FOUND, MODEL_SETTINGS_INVALID ->
                    error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request);
        };
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(status, "INVALID_REQUEST", request);
    }
}
