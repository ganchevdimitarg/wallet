package com.ganchevdimitarg.wallet.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleInsufficientFunds_returns409() {
        var ex = new InsufficientFundsException(new BigDecimal("50.00"), new BigDecimal("100.00"));

        ProblemDetail pd = handler.handleInsufficientFunds(ex);

        assertThat(pd.getStatus()).isEqualTo(409);
        assertThat(pd.getDetail()).contains("50.00", "100.00");
        assertThat(pd.getType().toString())
                .isEqualTo("https://wallet.ganchevdimitarg.com/errors/insufficient-funds");
    }

    @Test
    void handleWalletNotFound_returns404() {
        UUID playerId = UUID.randomUUID();
        var ex = new WalletNotFoundException(playerId);

        ProblemDetail pd = handler.handleWalletNotFound(ex);

        assertThat(pd.getStatus()).isEqualTo(404);
        assertThat(pd.getDetail()).contains(playerId.toString());
        assertThat(pd.getType().toString())
                .isEqualTo("https://wallet.ganchevdimitarg.com/errors/wallet-not-found");
    }

    @Test
    void handleServiceUnavailable_returns503() {
        var ex = new ServiceUnavailableException("DB is down");

        ProblemDetail pd = handler.handleServiceUnavailable(ex);

        assertThat(pd.getStatus()).isEqualTo(503);
        assertThat(pd.getDetail()).isEqualTo("DB is down");
        assertThat(pd.getType().toString())
                .isEqualTo("https://wallet.ganchevdimitarg.com/errors/service-unavailable");
    }

    @Test
    void handleValidation_returns400_withFieldErrors() throws Exception {
        // Simulate binding a WithdrawalRequest with null playerId and zero amount
        Object target = new Object(); // the actual target isn't inspected — only field errors are
        var bindingResult = new BeanPropertyBindingResult(target, "withdrawalRequest");
        bindingResult.addError(new FieldError("withdrawalRequest", "playerId", "playerId is required"));
        bindingResult.addError(
                new FieldError("withdrawalRequest", "amount", "amount must be greater than zero"));

        var ex = new MethodArgumentNotValidException(null, bindingResult);

        ProblemDetail pd = handler.handleValidation(ex);

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail())
                .contains("playerId: playerId is required")
                .contains("amount: amount must be greater than zero");
    }
}
