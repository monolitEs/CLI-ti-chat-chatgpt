package org.monolit.clitochatchatgpt.controller;

import jakarta.validation.Valid;
import org.monolit.clitochatchatgpt.model.dto.ChatCompletionRequest;
import org.monolit.clitochatchatgpt.model.dto.ChatMessage;
import org.monolit.clitochatchatgpt.model.records.ChatCompletionChoice;
import org.monolit.clitochatchatgpt.model.records.ChatCompletionResponse;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class ChatCompletionsController {

    private final ChatService chatService;

    public ChatCompletionsController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse complete(@Valid @RequestBody ChatCompletionRequest request,
                                           @RequestAttribute(ChatRequestFilter.REQUEST_ID_ATTRIBUTE) String requestId) {
        var answer = chatService.complete(requestId, request.model(), request.messages());
        return new ChatCompletionResponse("chatcmpl-" + requestId, "chat.completion",
                Instant.now().getEpochSecond(), request.model(),
                List.of(new ChatCompletionChoice(0, new ChatMessage("assistant", answer), "stop")));
    }
}
