package com.ganchevdimitarg.wallet.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyStoreTest {

    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> valueOps;

    private IdempotencyStore store;

    private static final String KEY = "idem:test-key";

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new IdempotencyStore(redis);
    }

    @Test
    void get_returnsValueWhenPresent() {
        when(valueOps.get(KEY)).thenReturn("{\"result\":\"ok\"}");

        assertThat(store.get("test-key"))
                .hasValue("{\"result\":\"ok\"}");
    }

    @Test
    void get_returnsEmptyWhenAbsent() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(store.get("test-key")).isEmpty();
    }

    @Test
    void setIfAbsent_returnsTrueOnFirstSet() {
        when(valueOps.setIfAbsent(KEY, "body", Duration.ofHours(24)))
                .thenReturn(true);

        assertThat(store.setIfAbsent("test-key", "body")).isTrue();
    }

    @Test
    void setIfAbsent_returnsFalseOnDuplicateSet() {
        when(valueOps.setIfAbsent(KEY, "body", Duration.ofHours(24)))
                .thenReturn(false);

        assertThat(store.setIfAbsent("test-key", "body")).isFalse();
    }

    @Test
    void setIfAbsent_handlesNullReturn() {
        when(valueOps.setIfAbsent(KEY, "body", Duration.ofHours(24)))
                .thenReturn(null);

        assertThat(store.setIfAbsent("test-key", "body")).isFalse();
    }

    @Test
    void save_storesValueWithTtl() {
        store.save("test-key", "cached-body");

        verify(valueOps).set(KEY, "cached-body", Duration.ofHours(24));
    }
}
