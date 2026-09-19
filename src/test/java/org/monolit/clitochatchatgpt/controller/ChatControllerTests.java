package org.monolit.clitochatchatgpt.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatController.class)
class ChatControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void returnsCodexAnswerWithServerRequestId() throws Exception {
        when(chatService.chat("Hello")).thenReturn("Hi");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(ChatController.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.answer").value("Hi"));
    }

    @Test
    void rejectsBlankMessageWithoutCallingCodex() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(ChatController.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value("MESSAGE_REQUIRED"));
    }

    @Test
    void reportsUnavailableCodexWithoutLeakingFailureDetails() throws Exception {
        when(chatService.chat("Hello"))
                .thenThrow(new ChatService.ChatUnavailableException(
                        new IllegalStateException("sensitive detail")));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(ChatController.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value("CODEX_UNAVAILABLE"));
    }
}
