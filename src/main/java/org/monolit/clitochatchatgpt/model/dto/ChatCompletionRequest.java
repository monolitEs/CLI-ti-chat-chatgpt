package org.monolit.clitochatchatgpt.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.monolit.clitochatchatgpt.model.serialization.StrictStringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

public record ChatCompletionRequest(
        @NotBlank @Size(max = 256) @JsonDeserialize(using = StrictStringDeserializer.class) String model,
        @NotEmpty List<@NotNull @Valid ChatMessage> messages,
        JsonNode stream, JsonNode n) {

    @AssertTrue
    @JsonIgnore
    public boolean isOptionsValid() {
        return (stream == null || stream.isBoolean() && !stream.booleanValue())
                && (n == null || n.isIntegralNumber() && n.canConvertToInt() && n.intValue() == 1);
    }

    @AssertTrue
    @JsonIgnore
    public boolean isHistoryValid() {
        if (messages == null || messages.isEmpty() || messages.contains(null)) {
            return true; // Field and element constraints handle missing messages.
        }
        boolean historyStarted = false;
        for (var message : messages) {
            if ("system".equals(message.role()) || "developer".equals(message.role())) {
                if (historyStarted) {
                    return false;
                }
            } else {
                historyStarted = true;
            }
        }
        return "user".equals(messages.getLast().role());
    }

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("Unsupported completion parameter");
    }
}
