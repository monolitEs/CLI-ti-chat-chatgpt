package org.monolit.clitochatchatgpt.model.exceptions;

import org.monolit.clitochatchatgpt.model.enums.ChatFailure;

public final class ChatException extends RuntimeException {
    private final ChatFailure failure;

    public ChatException(ChatFailure failure) {
        this.failure = failure;
    }

    public ChatException(ChatFailure failure, Throwable cause) {
        super(cause);
        this.failure = failure;
    }

    public ChatFailure failure() {
        return failure;
    }
}
