package org.monolit.clitochatchatgpt.service;

import lombok.extern.slf4j.Slf4j;
import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.monolit.clitochatchatgpt.model.enums.CodexFailure;
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
}
