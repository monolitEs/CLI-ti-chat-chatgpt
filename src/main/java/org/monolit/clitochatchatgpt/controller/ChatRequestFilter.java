package org.monolit.clitochatchatgpt.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Slf4j
public class ChatRequestFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "org.monolit.clitochatchatgpt.requestId";
    static final String ERROR_CODE_ATTRIBUTE = ChatRequestFilter.class.getName() + ".errorCode";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/chat").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        var started = System.nanoTime();
        var completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            var durationMs = (System.nanoTime() - started) / 1_000_000;
            if (completed && response.getStatus() < 400) {
                log.info("Chat request completed requestId={} durationMs={}", requestId, durationMs);
            } else {
                var code = request.getAttribute(ERROR_CODE_ATTRIBUTE);
                log.warn("Chat request failed requestId={} code={} durationMs={}",
                        requestId, code == null ? "HTTP_" + (completed ? response.getStatus() : 500) : code,
                        durationMs);
            }
        }
    }
}
