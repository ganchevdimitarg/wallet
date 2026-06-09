package com.ganchevdimitarg.wallet.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    // Only POST endpoints that mutate state need idempotency protection
    private static final Set<String> PROTECTED_PATHS = Set.of(
        "/api/v1/wallet/withdraw",
        "/api/v1/wallet/deposit"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // Skip non-POST requests and unprotected paths
        if (!shouldApply(request)) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader("X-Idempotency-Key");
        if (key == null || key.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                objectMapper.writeValueAsString(
                    "Missing required header: X-Idempotency-Key"));
            return;
        }

        // Fast path — key already in Redis, return cached response immediately
        String cached = store.get(key).orElse(null);
        if (cached != null) {
            log.debug("Idempotency hit for key={}", key);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(cached);
            return;
        }

        // Wrap the response so we can read the body after the controller writes it
        ContentCachingResponseWrapper wrappedResponse =
            new ContentCachingResponseWrapper(response);

        chain.doFilter(request, wrappedResponse);

        // Only cache successful responses — don't cache 4xx/5xx
        // setIfAbsent is atomic (Redis SET NX) — closes the race window where two
        // simultaneous requests both miss the get() above and both try to store
        if (wrappedResponse.getStatus() < 400) {
            String responseBody = new String(
                wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            boolean stored = store.setIfAbsent(key, responseBody);
            log.debug("Idempotency key={} stored={}", key, stored);
        }

        // Must call this — flushes the captured body to the actual response
        wrappedResponse.copyBodyToResponse();
    }

    private boolean shouldApply(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
            && PROTECTED_PATHS.contains(request.getRequestURI());
    }
}
