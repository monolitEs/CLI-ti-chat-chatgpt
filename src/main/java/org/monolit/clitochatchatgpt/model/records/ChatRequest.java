package org.monolit.clitochatchatgpt.model.records;

import tools.jackson.databind.JsonNode;

public record ChatRequest(String message, JsonNode conversationId) {
}
