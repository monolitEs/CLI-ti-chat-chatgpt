package org.monolit.clitochatchatgpt.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

@WebMvcTest(ChatController.class)
class ChatControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void returnsCodexAnswerWithServerRequestId() throws Exception {
        when(chatService.chat(anyString(), eq("Hello"))).thenReturn("Hi");

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

    @ParameterizedTest
    @MethodSource("failures")
    void mapsChatFailuresWithoutLeakingDetails(ChatFailure failure,
            int statusCode, String code) throws Exception {
        when(chatService.chat(anyString(), eq("Hello")))
                .thenThrow(new ChatException(failure,
                        new IllegalStateException("sensitive detail")));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().is(statusCode))
                .andExpect(header().exists(ChatController.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(ChatFailure.BUSY, 409, "CHAT_BUSY"),
                Arguments.of(ChatFailure.BAD_GATEWAY, 502, "CODEX_BAD_GATEWAY"),
                Arguments.of(ChatFailure.NOT_AUTHENTICATED, 503,
                        "CODEX_NOT_AUTHENTICATED"),
                Arguments.of(ChatFailure.UNAVAILABLE, 503, "CODEX_UNAVAILABLE"),
                Arguments.of(ChatFailure.TIMEOUT, 504, "CODEX_TIMEOUT"));
    }
}
