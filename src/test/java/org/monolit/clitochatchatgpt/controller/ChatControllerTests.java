package org.monolit.clitochatchatgpt.controller;

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
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
class ChatControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    private static Stream<String> missingMessages() {
        return Stream.of("", "null", "{}", "{\"message\":null}", "{\"message\":\"\"}",
                "{\"message\":\"\\t\\n\"}", "{\"message\":\"\\u2003\"}");
    }

    private static ResultMatcher matchingRequestId() {
        return result -> jsonPath("$.requestId")
                .value(result.getResponse().getHeader(ChatRequestFilter.REQUEST_ID_HEADER)).match(result);
    }

    private static Stream<String> invalidIds() {
        return Stream.of("\"\"", "\"  \"", "\"\\u2003\"", "123", "1.5", "true", "[]", "{}",
                "\"" + "a".repeat(257) + "\"", "\"" + "😀".repeat(129) + "\"");
    }

    @Test
    void returnsCodexAnswerWithServerRequestId() throws Exception {
        when(chatService.chat(anyString(), eq("Hello"), isNull())).thenReturn("Hi");

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(ChatRequestFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(matchingRequestId())
                .andExpect(jsonPath("$.answer").value("Hi"));
    }

    @Test
    void rejectsBlankMessageWithoutCallingCodex() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(ChatRequestFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value("MESSAGE_REQUIRED"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapsChatFailuresWithoutLeakingDetails(ChatFailure failure,
            int statusCode, String code) throws Exception {
        when(chatService.chat(anyString(), eq("Hello"), isNull()))
                .thenThrow(new ChatException(failure,
                        new IllegalStateException("sensitive detail")));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().is(statusCode))
                .andExpect(header().exists(ChatRequestFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(matchingRequestId())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive detail"))));
    }

    @ParameterizedTest
    @MethodSource("missingMessages")
    void rejectsMissingMessageBeforeCallingService(String body) throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MESSAGE_REQUIRED"))
                .andExpect(matchingRequestId());
        verifyNoInteractions(chatService);
    }

    @Test
    void rejectsMalformedJsonBeforeCallingService() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(ChatRequestFilter.REQUEST_ID_HEADER));
        verifyNoInteractions(chatService);
    }

    @Test
    void preservesMessageAndConversationIdAtTheLengthBoundary() throws Exception {
        var id = " " + "a".repeat(254) + " ";
        when(chatService.chat(anyString(), eq(" Hello "), eq(id))).thenReturn("Hi");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\" Hello \",\"conversationId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Hi"))
                .andExpect(matchingRequestId());
    }

    @Test
    void generatesAnIdInsteadOfTrustingTheIncomingHeader() throws Exception {
        when(chatService.chat(anyString(), eq("Hello"), isNull())).thenReturn("Hi");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .header(ChatRequestFilter.REQUEST_ID_HEADER, "caller-id")
                        .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(ChatRequestFilter.REQUEST_ID_HEADER,
                        org.hamcrest.Matchers.not("caller-id")))
                .andExpect(matchingRequestId());
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

    @Test
    void forwardsConversationIdWithoutChangingResponse() {
        when(chatService.chat(anyString(), eq("Hello"), eq("vk:peer:session"))).thenReturn("Remembered");
        assertDoesNotThrow(() -> mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"conversationId\":\"vk:peer:session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Remembered"))
                .andExpect(jsonPath("$.conversationId").doesNotExist()));
    }

    @Test
    void assignsRequestIdWithAnApplicationContextPath() throws Exception {
        when(chatService.chat(anyString(), eq("Hello"), isNull())).thenReturn("Hi");
        mvc.perform(post("/test/api/chat").contextPath("/test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(matchingRequestId());
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void rejectsInvalidConversationIdWithoutCallingService(String id) {
        assertDoesNotThrow(() -> mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"conversationId\":" + id + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(ChatRequestFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.code").value("CONVERSATION_ID_INVALID"))
                .andExpect(matchingRequestId()));
        verifyNoInteractions(chatService);
    }

    @Test
    void acceptsNullConversationIdAsOneShot() {
        when(chatService.chat(anyString(), eq("Hello"), isNull())).thenReturn("Hi");
        assertDoesNotThrow(() -> mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\",\"conversationId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("Hi")));
    }
}
