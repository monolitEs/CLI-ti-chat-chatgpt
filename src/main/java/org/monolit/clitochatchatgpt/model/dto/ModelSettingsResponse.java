package org.monolit.clitochatchatgpt.model.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ModelSettingsResponse {
    private final String requestId;
    private final String conversationId;
    private final String model;
    private final String reasoningEffort;
}
