package org.monolit.clitochatchatgpt.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.monolit.clitochatchatgpt.model.dto.ChatMessage;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatCompletionsController.class)
class ChatCompletionsControllerTests {

    private static final String URL = "/v1/chat/completions";
    private static final String MESSAGES = "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]";
    private static final String BODY = "{\"model\":\"m\"," + MESSAGES + "}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chat;

    static Stream<String> invalidRequests() {
        var envelopes = Stream.of("", "null", "{}", "[]", "true", "{", "{\"model\":true," + MESSAGES + "}",
                "{\"model\":123," + MESSAGES + "}", "{\"model\":\"  \"," + MESSAGES + "}",
                "{\"model\":\"" + "m".repeat(257) + "\"," + MESSAGES + "}");
        var messageValues = Stream.of("null", "[]", "{}", "[null]", "[{}]",
                        "[{\"role\":null,\"content\":\"Hi\"}]", "[{\"role\":123,\"content\":\"Hi\"}]",
                        "[{\"role\":\"tool\",\"content\":\"Hi\"}]", "[{\"role\":\"user\",\"content\":null}]",
                        "[{\"role\":\"user\",\"content\":123}]", "[{\"role\":\"user\",\"content\":\"  \\n\"}]",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}]}]",
                        "[{\"role\":\"assistant\",\"content\":\"Hi\"}]",
                        "[{\"role\":\"system\",\"content\":\"Hi\"}]",
                        "[{\"role\":\"user\",\"content\":\"Hi\",\"tool_calls\":[]}]",
                        "[{\"role\":\"user\",\"content\":\"Hi\"},{\"role\":\"developer\",\"content\":\"Policy\"},{\"role\":\"user\",\"content\":\"Next\"}]")
                .map(value -> "{\"model\":\"m\",\"messages\":" + value + "}");
        var options = Stream.of("\"stream\":true", "\"stream\":\"false\"", "\"stream\":0", "\"stream\":null",
                        "\"n\":2", "\"n\":\"1\"", "\"n\":1.0", "\"n\":4294967297", "\"n\":null",
                        "\"tools\":[]", "\"temperature\":0", "\"conversationId\":\"saved\"", "\"config\":{}",
                        "\"sandbox\":\"danger-full-access\"", "\"baseInstructions\":\"override\"",
                        "\"developerInstructions\":\"override\"")
                .map(option -> BODY.substring(0, BODY.length() - 1) + "," + option + "}");
        return Stream.concat(envelopes, Stream.concat(messageValues, options));
    }

    static Stream<ChatFailure> failures() {
        return Stream.of(ChatFailure.values());
    }

    @Test
    void returnsCompletionAndPreservesHistoryWithoutFabricatingUsage() throws Exception {
        var messages = List.of(new ChatMessage("system", "Policy"), new ChatMessage("developer", "Style"),
                new ChatMessage("user", " Hello "), new ChatMessage("assistant", "Hi"),
                new ChatMessage("user", "Next"));
        when(chat.complete(anyString(), eq("m"), eq(messages))).thenReturn("Answer");
        var body = """
                {"model":"m","stream":false,"n":1,"messages":[
                  {"role":"system","content":"Policy"},{"role":"developer","content":"Style"},
                  {"role":"user","content":" Hello "},{"role":"assistant","content":"Hi"},
                  {"role":"user","content":"Next"}]}
                """;
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Request-Id", "untrusted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.created").isNumber())
                .andExpect(jsonPath("$.model").value("m"))
                .andExpect(jsonPath("$.choices[0].index").value(0))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Answer"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage").doesNotExist())
                .andExpect(result -> {
                    var id = result.getResponse().getHeader("X-Request-Id");
                    assertThat(id).isNotBlank().isNotEqualTo("untrusted");
                    jsonPath("$.id").value("chatcmpl-" + id).match(result);
                });
        verify(chat).complete(anyString(), eq("m"), eq(messages));
        verifyNoMoreInteractions(chat);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidOrUnsupportedInputWithoutCallingBackend(String body) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(chat);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapsBackendErrorsWithoutLeakingCause(ChatFailure failure) throws Exception {
        var statusCode = switch (failure) {
            case BUSY -> 409;
            case BAD_GATEWAY -> 502;
            case NOT_AUTHENTICATED, UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            default -> 400;
        };
        when(chat.complete(anyString(), anyString(), anyList()))
                .thenThrow(new ChatException(failure, new IllegalStateException("SECRET_DETAILS")));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(statusCode))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").isString())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("SECRET_DETAILS"));
    }
}
