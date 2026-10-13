package org.monolit.clitochatchatgpt.utils;

import org.monolit.clitochatchatgpt.client.CodexAppServerClient;
import org.springframework.beans.BeanUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

public class FakeCodexProcess extends Process {
    private final InputStream stdout;
    private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
    private boolean alive = true;

    public FakeCodexProcess(String stdout) {
        this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
    }

    public static CodexAppServerClient client(ObjectMapper mapper, Supplier<Process> processes) {
        try {
            var starterType = Class.forName(CodexAppServerClient.class.getName() + "$ProcessStarter");
            var starter = mock(starterType, invocation -> "start".equals(invocation.getMethod().getName())
                    ? processes.get() : RETURNS_DEFAULTS.answer(invocation));
            var constructor = CodexAppServerClient.class.getDeclaredConstructor(ObjectMapper.class, starterType);
            return BeanUtils.instantiateClass(constructor, mapper, starter);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create test Codex client", exception);
        }
    }

    public static String protocolPrefix() {
        return """
                {"id":1,"result":{"userAgent":"test"}}
                {"id":2,"result":{"account":{"type":"chatgpt"},"requiresOpenaiAuth":true}}
                {"id":3,"result":{"thread":{"id":"thr_1"}}}
                {"id":4,"result":{"turn":{"id":"turn_1","status":"inProgress","items":[]}}}
                """;
    }

    public String requests() {
        return stdin.toString(StandardCharsets.UTF_8);
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
        return 0;
    }

    @Override
    public int exitValue() {
        return 0;
    }

    @Override
    public void destroy() {
        alive = false;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    public CodexAppServerClient client(ObjectMapper mapper) {
        return client(mapper, () -> this);
    }

}
