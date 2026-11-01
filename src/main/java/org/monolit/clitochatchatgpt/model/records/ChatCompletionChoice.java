package org.monolit.clitochatchatgpt.model.records;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.monolit.clitochatchatgpt.model.dto.ChatMessage;

public record ChatCompletionChoice(int index, ChatMessage message,
                                   @JsonProperty("finish_reason") String finishReason) {
}
