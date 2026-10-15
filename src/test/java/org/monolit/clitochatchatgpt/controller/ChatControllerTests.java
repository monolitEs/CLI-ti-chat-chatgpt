package org.monolit.clitochatchatgpt.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.monolit.clitochatchatgpt.model.dto.ModelSettingsResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

    private static Stream<String> invalidSettingsBodies() {
        return Stream.of("", "null", "{}", "{", "[]", "{\"model\":\"m\"}", "{\"reasoningEffort\":\"high\"}",
                "{\"model\":null,\"reasoningEffort\":\"high\"}", "{\"model\":\"m\",\"reasoningEffort\":null}",
                "{\"model\":\"\\u2003\",\"reasoningEffort\":\"high\"}", "{\"model\":\"m\",\"reasoningEffort\":\"  \"}",
                "{\"model\":123,\"reasoningEffort\":\"high\"}", "{\"model\":\"m\",\"reasoningEffort\":true}",
                "{\"model\":[],\"reasoningEffort\":\"high\"}", "{\"model\":\"m\",\"reasoningEffort\":{}}",
                "{\"model\":\"" + "m".repeat(257) + "\",\"reasoningEffort\":\"high\"}",
                "{\"model\":\"m\",\"reasoningEffort\":\"" + "h".repeat(257) + "\"}");
    }

    private static Stream<Arguments> settingsFailures() {
        return Stream.concat(failures(), Stream.of(
                Arguments.of(ChatFailure.CONVERSATION_NOT_FOUND, 404, "CONVERSATION_NOT_FOUND"),
                Arguments.of(ChatFailure.MODEL_SETTINGS_INVALID, 400, "MODEL_SETTINGS_INVALID")));
    }

    @Test
    void readsAndUpdatesExistingDialogueSettingsWithRequestIds() throws Exception {
        when(chatService.getSettings(anyString(), eq("a"))).thenAnswer(invocation ->
                new ModelSettingsResponse(invocation.getArgument(0), "a", null, null));
        mvc.perform(get("/api/chat/settings").param("conversationId", "a"))
                .andExpect(status().isOk()).andExpect(matchingRequestId())
                .andExpect(jsonPath("$.conversationId").value("a"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.reasoningEffort").doesNotExist());
        when(chatService.updateSettings(anyString(), eq("a"), eq("model"), eq("high")))
                .thenAnswer(invocation -> new ModelSettingsResponse(invocation.getArgument(0), "a", "model", "high"));
        mvc.perform(put("/api/chat/settings").param("conversationId", "a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"model\",\"reasoningEffort\":\"high\"}"))
                .andExpect(status().isOk()).andExpect(matchingRequestId())
                .andExpect(jsonPath("$.model").value("model"))
                .andExpect(jsonPath("$.reasoningEffort").value("high"));
    }

    @ParameterizedTest
    @MethodSource("invalidSettingsBodies")
    void rejectsInvalidSettingsBodyBeforeService(String body) throws Exception {
        mvc.perform(put("/api/chat/settings").param("conversationId", "a")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(matchingRequestId())
                .andExpect(jsonPath("$.code").value("MODEL_SETTINGS_INVALID"));
        verifyNoInteractions(chatService);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "\u2003"})
    void rejectsMissingOrBlankSettingsQueryForBothMethods(String id) throws Exception {
        for (var request : java.util.List.of(get("/api/chat/settings"), put("/api/chat/settings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"m\",\"reasoningEffort\":\"high\"}"))) {
            if (id != null) {
                request.param("conversationId", id);
            }
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(matchingRequestId())
                    .andExpect(jsonPath("$.code").value("CONVERSATION_ID_INVALID"));
        }
        verifyNoInteractions(chatService);
    }

    @Test
    void validatesSettingsQueryLengthAndContextPath() throws Exception {
        mvc.perform(get("/api/chat/settings").param("conversationId", "a".repeat(257)))
                .andExpect(status().isBadRequest()).andExpect(matchingRequestId())
                .andExpect(jsonPath("$.code").value("CONVERSATION_ID_INVALID"));
        when(chatService.getSettings(anyString(), eq("a".repeat(256)))).thenAnswer(invocation ->
                new ModelSettingsResponse(invocation.getArgument(0), invocation.getArgument(1), "m", "high"));
        mvc.perform(get("/test/api/chat/settings").contextPath("/test")
                        .param("conversationId", "a".repeat(256)))
                .andExpect(status().isOk()).andExpect(matchingRequestId());
    }

    @ParameterizedTest
    @MethodSource("settingsFailures")
    void mapsSettingsFailuresWithoutDetails(ChatFailure failure, int statusCode, String code) throws Exception {
        when(chatService.getSettings(anyString(), eq("a")))
                .thenThrow(new ChatException(failure, new IllegalStateException("sensitive")));
        when(chatService.updateSettings(anyString(), eq("a"), eq("m"), eq("high")))
                .thenThrow(new ChatException(failure, new IllegalStateException("sensitive")));
        for (var request : java.util.List.of(get("/api/chat/settings"), put("/api/chat/settings")
                .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"m\",\"reasoningEffort\":\"high\"}"))) {
            mvc.perform(request.param("conversationId", "a")).andExpect(status().is(statusCode))
                    .andExpect(matchingRequestId()).andExpect(jsonPath("$.code").value(code))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
        }
    }
}
