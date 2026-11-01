package org.monolit.clitochatchatgpt.model.records;

import java.util.List;

public record ChatCompletionResponse(String id, String object, long created, String model,
                                     List<ChatCompletionChoice> choices) {
}
