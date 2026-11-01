package org.monolit.clitochatchatgpt.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.monolit.clitochatchatgpt.model.serialization.StrictStringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ChatMessage(
        @NotNull @Pattern(regexp = "system|developer|user|assistant")
        @JsonDeserialize(using = StrictStringDeserializer.class) String role,
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String content) {

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("Unsupported message parameter");
    }
}
