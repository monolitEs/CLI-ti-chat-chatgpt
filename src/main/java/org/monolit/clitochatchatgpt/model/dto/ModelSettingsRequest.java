package org.monolit.clitochatchatgpt.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.monolit.clitochatchatgpt.model.serialization.StrictStringDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class ModelSettingsRequest {

    @NotBlank
    @Size(max = 256)
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String model;

    @NotBlank
    @Size(max = 256)
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String reasoningEffort;
}
