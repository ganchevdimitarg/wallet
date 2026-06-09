package com.ganchevdimitarg.wallet.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private final StringRedisTemplate redis;
    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idem:";

    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + key));
    }

    /**
     * Atomic check-and-set using SET NX (set if not exists).
     * Returns true if this is the first time the key is being stored,
     * false if it already existed — i.e. a duplicate request.
     *
     * This prevents a race condition where two identical requests
     * arrive simultaneously, both miss the GET check, and both proceed
     * to execute the transaction.
     */
    public boolean setIfAbsent(String key, String responseBody) {
        Boolean stored = redis.opsForValue()
            .setIfAbsent(KEY_PREFIX + key, responseBody, TTL);
        return Boolean.TRUE.equals(stored);
    }

    public void save(String key, String responseBody) {
        redis.opsForValue().set(KEY_PREFIX + key, responseBody, TTL);
    }
}
