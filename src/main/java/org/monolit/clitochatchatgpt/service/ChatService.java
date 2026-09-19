package org.monolit.clitochatchatgpt.service;

import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final CodexAppServerClient codex;

    public ChatService(CodexAppServerClient codex) {
        this.codex = codex;
    }

    public String chat(String message) {
        try {
            return codex.chat(message);
        } catch (CodexAppServerClient.CodexException exception) {
            throw new ChatUnavailableException(exception);
        }
    }

    public static final class ChatUnavailableException extends RuntimeException {
        public ChatUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
