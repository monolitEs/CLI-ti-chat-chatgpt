package org.monolit.clitochatchatgpt.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.monolit.clitochatchatgpt.model.serialization.StrictStringDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@Getter
@Setter
public class ChatRequest {

    @NotBlank
    private String message;

    @Size(max = 256)
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String conversationId;

    @AssertTrue
    @JsonIgnore
    public boolean isConversationIdValid() {
        return conversationId == null || !conversationId.isBlank();
    }
}
