package org.monolit.clitochatchatgpt.controller;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.model.records.ChatError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.DatabindException;

@RestControllerAdvice(assignableTypes = ChatController.class)
@NullMarked
public class ChatExceptionHandler extends ResponseEntityExceptionHandler {

    private static ResponseEntity<Object> error(HttpStatus status, String code, WebRequest request) {
        var requestId = (String) request.getAttribute(ChatRequestFilter.REQUEST_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        request.setAttribute(ChatRequestFilter.ERROR_CODE_ATTRIBUTE, code, RequestAttributes.SCOPE_REQUEST);
        return ResponseEntity.status(status).body(new ChatError(requestId, code));
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Object> handleChatFailure(ChatException exception, WebRequest request) {
        return switch (exception.failure()) {
            case BUSY -> error(HttpStatus.CONFLICT, "CHAT_BUSY", request);
            case BAD_GATEWAY -> error(HttpStatus.BAD_GATEWAY, "CODEX_BAD_GATEWAY", request);
            case NOT_AUTHENTICATED -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "CODEX_NOT_AUTHENTICATED", request);
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_UNAVAILABLE", request);
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "CODEX_TIMEOUT", request);
        };
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var code = exception.getBindingResult().hasFieldErrors("message")
                ? "MESSAGE_REQUIRED" : "CONVERSATION_ID_INVALID";
        return error(HttpStatus.BAD_REQUEST, code, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.getCause() == null) {
            return error(HttpStatus.BAD_REQUEST, "MESSAGE_REQUIRED", request);
        }
        if (exception.getCause() instanceof DatabindException mappingException
                && mappingException.getPath().stream()
                .anyMatch(reference -> "conversationId".equals(reference.getPropertyName()))) {
            return error(HttpStatus.BAD_REQUEST, "CONVERSATION_ID_INVALID", request);
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }
}
