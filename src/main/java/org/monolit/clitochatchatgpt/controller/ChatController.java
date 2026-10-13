package org.monolit.clitochatchatgpt.controller;

import jakarta.validation.Valid;
import org.monolit.clitochatchatgpt.model.dto.ChatRequest;
import org.monolit.clitochatchatgpt.model.records.ChatResponse;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request,
                             @RequestAttribute(ChatRequestFilter.REQUEST_ID_ATTRIBUTE) String requestId) {
        var answer = chatService.chat(requestId, request.getMessage(), request.getConversationId());
        return new ChatResponse(requestId, answer);
    }
}
