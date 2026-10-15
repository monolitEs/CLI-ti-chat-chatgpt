package org.monolit.clitochatchatgpt.service;

import lombok.extern.slf4j.Slf4j;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.dto.ModelSettingsResponse;
import org.monolit.clitochatchatgpt.model.enums.ChatFailure;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
import org.monolit.clitochatchatgpt.model.exceptions.ChatException;
import org.monolit.clitochatchatgpt.model.exceptions.CodexException;
import org.monolit.clitochatchatgpt.persistence.ConversationStore;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
public class ConversationService {

    private final CodexAppServerClient codex;
    private final ConversationStore conversations;

    public ConversationService(CodexAppServerClient codex, ConversationStore conversations) {
        this.codex = codex;
        this.conversations = conversations;
    }

    public String chat(String requestId, String message, String conversationId) {
        codex.ensureStarted();
        String threadId;
        try {
            threadId = prepareThread(conversationId);
        } catch (IOException | RuntimeException exception) {
            codex.reset();
            log.warn("Codex turn failed requestId={} code={}", requestId,
                    exception instanceof CodexException codexException
                            ? codexException.kind() : CodexFailure.UNAVAILABLE);
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException(CodexFailure.UNAVAILABLE, "Conversation preparation failed", exception);
        }
        return codex.runTurn(requestId, message, threadId);
    }

    private String prepareThread(String conversationId) throws IOException {
        if (conversationId == null) {
            return codex.startThread(true);
        }
        var storedId = conversations.find(conversationId);
        if (storedId != null) {
            return codex.resumeThread(storedId);
        }
        var threadId = codex.startThread(false);
        conversations.save(conversationId, threadId);
        return threadId;
    }

    public ModelSettingsResponse getSettings(String requestId, String conversationId) {
        return settings(requestId, conversationId, null, null);
    }

    public ModelSettingsResponse updateSettings(String requestId, String conversationId, String model, String effort) {
        return settings(requestId, conversationId, model, effort);
    }

    private ModelSettingsResponse settings(String requestId, String conversationId, String model, String effort) {
        try {
            var threadId = conversations.find(conversationId);
            if (threadId == null) {
                throw new ChatException(ChatFailure.CONVERSATION_NOT_FOUND);
            }
            codex.ensureStarted();
            ModelSettingsResponse result;
            if (model == null) {
                result = codex.readSettings(threadId);
            } else {
                if (!codex.supportsSettings(model, effort)) {
                    throw new ChatException(ChatFailure.MODEL_SETTINGS_INVALID);
                }
                result = codex.updateSettings(threadId, model, effort);
            }
            return new ModelSettingsResponse(requestId, conversationId, result.getModel(), result.getReasoningEffort());
        } catch (ChatException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            codex.reset();
            log.warn("Codex settings failed requestId={} code={}", requestId,
                    exception instanceof CodexException codexException
                            ? codexException.kind() : CodexFailure.UNAVAILABLE);
            if (exception instanceof CodexException codexException) {
                throw codexException;
            }
            throw new CodexException(CodexFailure.UNAVAILABLE, "Codex settings failed", exception);
        }
    }
}
