package org.monolit.clitochatchatgpt.model.exceptions;

import org.monolit.clitochatchatgpt.model.enums.CodexFailure;

public final class CodexException extends RuntimeException {
    private final CodexFailure failure;

    public CodexException(CodexFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public CodexException(CodexFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public CodexFailure kind() {
        return failure;
    }
}
