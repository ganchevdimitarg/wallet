package com.ganchevdimitarg.wallet.filter;

import com.ganchevdimitarg.wallet.AbstractWalletIntegrationTest;
import com.ganchevdimitarg.wallet.model.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyFilterIntegrationTest extends AbstractWalletIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Filter skip: non-POST and unprotected paths ───────────────────────────

    @Test
    void should_allowGetRequest_when_idempotencyKeyNotProvided() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        ResponseEntity<String> response = restClient.get()
                .uri("/api/v1/wallet/{playerId}/balance", wallet.getPlayerId())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(response.getBody())).isEqualByComparingTo("100.0000");
    }

    // ── Missing / blank idempotency key ───────────────────────────────────────

    @Test
    void should_return400_when_idempotencyKeyIsBlank_onWithdraw() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/v1/wallet/withdraw")
                        .header("X-Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(wallet.getPlayerId(), "30.00"))
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Missing required header: X-Idempotency-Key");
                });

        // Balance must be unchanged — filter rejected before service executed
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId())))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void should_return400_when_idempotencyKeyIsBlank_onDeposit() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/v1/wallet/deposit")
                        .header("X-Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(wallet.getPlayerId(), "50.00"))
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Missing required header: X-Idempotency-Key");
                });

        // Balance must be unchanged
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId())))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void should_return400_when_idempotencyKeyIsEmpty_onWithdraw() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/v1/wallet/withdraw")
                        .header("X-Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(wallet.getPlayerId(), "30.00"))
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Missing required header: X-Idempotency-Key");
                });
    }

    // ── Redis caching of successful responses ─────────────────────────────────

    @Test
    void should_cacheSuccessfulResponseInRedis_when_withdrawSucceeds() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        postJson("/api/v1/wallet/withdraw", idempotencyKey,
                requestBody(wallet.getPlayerId(), "30.00"));

        // Filter must have stored the response in Redis under idem:<key>
        String cached = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cached).isNotNull();
        assertThat(cached).contains("\"transactionId\"");
        assertThat(cached).contains("\"balanceAfter\"");
    }

    @Test
    void should_cacheSuccessfulResponseInRedis_when_depositSucceeds() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        postJson("/api/v1/wallet/deposit", idempotencyKey,
                requestBody(wallet.getPlayerId(), "50.00"));

        String cached = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cached).isNotNull();
        assertThat(cached).contains("\"transactionId\"");
        assertThat(cached).contains("\"balanceAfter\"");
    }

    // ── Error responses must NOT be cached ────────────────────────────────────

    @Test
    void should_notCacheErrorResponseInRedis_when_withdrawFailsWithInsufficientFunds() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("10.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() ->
                postJson("/api/v1/wallet/withdraw", idempotencyKey,
                        requestBody(wallet.getPlayerId(), "100.00")))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                });

        // Error response (409) must NOT be stored in Redis
        String cached = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cached).isNull();
    }

    @Test
    void should_notCacheErrorResponseInRedis_when_walletNotFound() {
        String idempotencyKey = UUID.randomUUID().toString();

        assertThatThrownBy(() ->
                postJson("/api/v1/wallet/withdraw", idempotencyKey,
                        requestBody(UUID.randomUUID(), "50.00")))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        // Error response (404) must NOT be stored in Redis
        String cached = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cached).isNull();
    }

    // ── Filter fast path: serve cached response without hitting controller ────

    @Test
    void should_serveCachedResponseFromRedis_when_duplicateWithdrawRequestArrives() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();
        String body = requestBody(wallet.getPlayerId(), "30.00");

        // First request: populate Redis cache via filter
        ResponseEntity<String> first = postJson("/api/v1/wallet/withdraw",
                idempotencyKey, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify the response is stored in Redis before the duplicate arrives
        String cachedBefore = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cachedBefore).isNotNull();

        // Second request: filter returns cached response directly from Redis
        ResponseEntity<String> second = postJson("/api/v1/wallet/withdraw",
                idempotencyKey, body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(cachedBefore);

        // Balance must remain at 70.00 — the second request was swallowed by the filter
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId())))
                .isEqualByComparingTo("70.00");
    }

    @Test
    void should_serveCachedResponseFromRedis_when_duplicateDepositRequestArrives() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();
        String body = requestBody(wallet.getPlayerId(), "50.00");

        ResponseEntity<String> first = postJson("/api/v1/wallet/deposit",
                idempotencyKey, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        String cachedBefore = redis.opsForValue().get("idem:" + idempotencyKey);
        assertThat(cachedBefore).isNotNull();

        ResponseEntity<String> second = postJson("/api/v1/wallet/deposit",
                idempotencyKey, body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(cachedBefore);

        // Balance must remain at 150.00 — duplicate was swallowed by the filter
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId())))
                .isEqualByComparingTo("150.00");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> postJson(String uri, String idempotencyKey,
                                            String requestBody) {
        return restClient.post()
                .uri(uri)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .toEntity(String.class);
    }

    private String getBalance(UUID playerId) {
        return restClient.get()
                .uri("/api/v1/wallet/{playerId}/balance", playerId)
                .retrieve()
                .body(String.class);
    }

    private String requestBody(UUID playerId, String amount) {
        return """
                {
                    "playerId": "%s",
                    "amount": %s
                }
                """.formatted(playerId, amount);
    }
}
