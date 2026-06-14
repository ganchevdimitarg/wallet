package com.ganchevdimitarg.wallet.controller;

import com.ganchevdimitarg.wallet.AbstractWalletIntegrationTest;
import com.ganchevdimitarg.wallet.model.Wallet;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletControllerIntegrationTest extends AbstractWalletIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    // ── Full happy path ──────────────────────────────────────────────────────

    @Test
    void should_completeFullWalletFlow_when_depositWithdrawAndBalanceAreValid() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.0000"));

        String initialBalance = getBalance(wallet.getPlayerId());
        assertThat(initialBalance).isEqualTo("100.0000");

        ResponseEntity<String> depositResponse = postJson(
                "/api/v1/wallet/deposit",
                UUID.randomUUID().toString(),
                requestBody(wallet.getPlayerId(), "50.2500")
        );

        assertThat(depositResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(depositResponse.getBody(), "$.transactionId")).isNotBlank();
        assertThat(JsonPath.<Double>read(depositResponse.getBody(), "$.balanceAfter")).isEqualTo(150.25);

        String balanceAfterDeposit = getBalance(wallet.getPlayerId());
        assertThat(new BigDecimal(balanceAfterDeposit)).isEqualByComparingTo("150.2500");

        ResponseEntity<String> withdrawResponse = postJson(
                "/api/v1/wallet/withdraw",
                UUID.randomUUID().toString(),
                requestBody(wallet.getPlayerId(), "25.1250")
        );

        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(withdrawResponse.getBody(), "$.transactionId")).isNotBlank();
        assertThat(JsonPath.<Double>read(withdrawResponse.getBody(), "$.balanceAfter")).isEqualTo(125.125);

        String finalBalance = getBalance(wallet.getPlayerId());
        assertThat(new BigDecimal(finalBalance)).isEqualByComparingTo("125.1250");
    }

    // ── GET /{playerId}/balance ──────────────────────────────────────────────

    @Test
    void should_return200AndBalance_when_walletExists() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        ResponseEntity<String> response = restClient.get()
                .uri("/api/v1/wallet/{playerId}/balance", wallet.getPlayerId())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(response.getBody())).isEqualByComparingTo("100.0000");    }

    @Test
    void should_return404_when_walletNotFound_duringGetBalance() {
        UUID nonExistentPlayerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/api/v1/wallet/{playerId}/balance", nonExistentPlayerId)
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Wallet not found for player: " + nonExistentPlayerId);
                });
    }

    @Test
    void should_return400_when_playerIdPathVariableIsMalformed_duringGetBalance() {
        assertThatThrownBy(() ->
                restClient.get()
                        .uri("/api/v1/wallet/not-a-uuid/balance")
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    // ── POST /withdraw ───────────────────────────────────────────────────────

    @Test
    void should_return200AndUpdatedBalance_when_withdrawIsSuccessful() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<String> response = postJson(
                "/api/v1/wallet/withdraw",
                idempotencyKey,
                requestBody(wallet.getPlayerId(), "30.00")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(JsonPath.<String>read(response.getBody(), "$.transactionId")).isNotBlank();
        assertThat(JsonPath.<Double>read(response.getBody(), "$.balanceAfter")).isEqualTo(70.00);
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("70.00");
    }

    @Test
    void should_return409_when_insufficientFunds_duringWithdraw() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("20.00"));

        assertThatThrownBy(() ->
                postJson(
                        "/api/v1/wallet/withdraw",
                        UUID.randomUUID().toString(),
                        requestBody(wallet.getPlayerId(), "100.00")
                ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(httpEx.getResponseBodyAsString()).contains("Insufficient funds");
                });

        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("20.00");
    }

    @Test
    void should_return404_when_walletNotFound_duringWithdraw() {
        UUID nonExistentPlayerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                postJson(
                        "/api/v1/wallet/withdraw",
                        UUID.randomUUID().toString(),
                        requestBody(nonExistentPlayerId, "50.00")
                ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Wallet not found for player: " + nonExistentPlayerId);
                });
    }

    @Test
    void should_return200AndSameResult_when_withdrawWithDuplicateIdempotencyKey() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = requestBody(wallet.getPlayerId(), "30.00");

        ResponseEntity<String> firstResponse = postJson(
                "/api/v1/wallet/withdraw",
                idempotencyKey,
                requestBody
        );

        String firstTxId = JsonPath.read(firstResponse.getBody(), "$.transactionId");
        assertThat(JsonPath.<Double>read(firstResponse.getBody(), "$.balanceAfter")).isEqualTo(70.00);

        ResponseEntity<String> secondResponse = postJson(
                "/api/v1/wallet/withdraw",
                idempotencyKey,
                requestBody
        );

        assertThat(JsonPath.<String>read(secondResponse.getBody(), "$.transactionId")).isEqualTo(firstTxId);
        assertThat(JsonPath.<Double>read(secondResponse.getBody(), "$.balanceAfter")).isEqualTo(70.00);
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("70.00");
    }

    @Test
    void should_allowOnlyOneWithdrawal_when_twoConcurrentWithdrawalsCompeteForSameFunds() throws Exception {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<HttpStatus> withdrawTask = () -> {
            startGate.await();

            try {
                postJson(
                        "/api/v1/wallet/withdraw",
                        UUID.randomUUID().toString(),
                        requestBody(wallet.getPlayerId(), "80.00")
                );
                return HttpStatus.OK;
            } catch (HttpClientErrorException ex) {
                return HttpStatus.valueOf(ex.getStatusCode().value());
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(withdrawTask);
            var second = executor.submit(withdrawTask);

            startGate.countDown();

            assertThat(first.get()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(second.get()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
            assertThat(first.get()).isNotEqualTo(second.get());
        }

        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("20.00");
    }

    // ── POST /deposit ────────────────────────────────────────────────────────

    @Test
    void should_return200AndUpdatedBalance_when_depositIsSuccessful() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));

        ResponseEntity<String> response = postJson(
                "/api/v1/wallet/deposit",
                UUID.randomUUID().toString(),
                requestBody(wallet.getPlayerId(), "50.00")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<Double>read(response.getBody(), "$.balanceAfter")).isEqualTo(150.00);
        assertThat(JsonPath.<String>read(response.getBody(), "$.transactionId")).isNotBlank();
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("150.00");
    }

    @Test
    void should_return404_when_walletNotFound_duringDeposit() {
        UUID nonExistentPlayerId = UUID.randomUUID();

        assertThatThrownBy(() ->
                postJson(
                        "/api/v1/wallet/deposit",
                        UUID.randomUUID().toString(),
                        requestBody(nonExistentPlayerId, "50.00")
                ))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(httpEx.getResponseBodyAsString())
                            .contains("Wallet not found for player: " + nonExistentPlayerId);
                });
    }

    @Test
    void should_return200AndSameResult_when_depositWithDuplicateIdempotencyKey() {
        Wallet wallet = createWalletWithBalance(new BigDecimal("100.00"));
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = requestBody(wallet.getPlayerId(), "50.00");

        ResponseEntity<String> firstResponse = postJson(
                "/api/v1/wallet/deposit",
                idempotencyKey,
                requestBody
        );

        String firstTxId = JsonPath.read(firstResponse.getBody(), "$.transactionId");
        assertThat(JsonPath.<Double>read(firstResponse.getBody(), "$.balanceAfter")).isEqualTo(150.00);

        ResponseEntity<String> secondResponse = postJson(
                "/api/v1/wallet/deposit",
                idempotencyKey,
                requestBody
        );

        assertThat(JsonPath.<String>read(secondResponse.getBody(), "$.transactionId")).isEqualTo(firstTxId);
        assertThat(JsonPath.<Double>read(secondResponse.getBody(), "$.balanceAfter")).isEqualTo(150.00);
        assertThat(new BigDecimal(getBalance(wallet.getPlayerId()))).isEqualByComparingTo("150.00");
    }

    // ── Validation edge cases ────────────────────────────────────────────────

    @Test
    void should_return400_when_missingIdempotencyHeader_duringWithdraw() {
        assertThatThrownBy(() ->
                restClient.post()
                        .uri("/api/v1/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(UUID.randomUUID(), "50.00"))
                        .retrieve()
                        .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void should_return400_when_playerIdIsNull_duringWithdraw() {
        assertBadRequestFromPost(
                "/api/v1/wallet/withdraw",
                """
                {
                    "playerId": null,
                    "amount": 50.00
                }
                """,
                "playerId"
        );
    }

    @Test
    void should_return400_when_amountIsNull_duringWithdraw() {
        assertBadRequestFromPost(
                "/api/v1/wallet/withdraw",
                """
                {
                    "playerId": "%s",
                    "amount": null
                }
                """.formatted(UUID.randomUUID()),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountIsZero_duringWithdraw() {
        assertBadRequestFromPost(
                "/api/v1/wallet/withdraw",
                requestBody(UUID.randomUUID(), "0.00"),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountIsNegative_duringWithdraw() {
        assertBadRequestFromPost(
                "/api/v1/wallet/withdraw",
                requestBody(UUID.randomUUID(), "-1.00"),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountHasTooManyFractionDigits_duringWithdraw() {
        assertBadRequestFromPost(
                "/api/v1/wallet/withdraw",
                requestBody(UUID.randomUUID(), "1.12345"),
                "amount"
        );
    }

    @Test
    void should_return400_when_playerIdIsNull_duringDeposit() {
        assertBadRequestFromPost(
                "/api/v1/wallet/deposit",
                """
                {
                    "playerId": null,
                    "amount": 50.00
                }
                """,
                "playerId"
        );
    }

    @Test
    void should_return400_when_amountIsNull_duringDeposit() {
        assertBadRequestFromPost(
                "/api/v1/wallet/deposit",
                """
                {
                    "playerId": "%s",
                    "amount": null
                }
                """.formatted(UUID.randomUUID()),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountIsZero_duringDeposit() {
        assertBadRequestFromPost(
                "/api/v1/wallet/deposit",
                requestBody(UUID.randomUUID(), "0.00"),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountIsNegative_duringDeposit() {
        assertBadRequestFromPost(
                "/api/v1/wallet/deposit",
                requestBody(UUID.randomUUID(), "-1.00"),
                "amount"
        );
    }

    @Test
    void should_return400_when_amountHasTooManyFractionDigits_duringDeposit() {
        assertBadRequestFromPost(
                "/api/v1/wallet/deposit",
                requestBody(UUID.randomUUID(), "1.12345"),
                "amount"
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> postJson(String uri, String idempotencyKey, String requestBody) {
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

    private void assertBadRequestFromPost(String uri, String requestBody, String expectedBodyFragment) {
        assertThatThrownBy(() ->
                postJson(uri, UUID.randomUUID().toString(), requestBody))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    var httpEx = (HttpClientErrorException) ex;
                    assertThat(httpEx.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(httpEx.getResponseBodyAsString()).contains(expectedBodyFragment);
                });
    }
}