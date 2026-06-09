package com.ganchevdimitarg.wallet.controller;

import com.ganchevdimitarg.wallet.dto.WithdrawalRequest;
import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletControllerTest {

    @Mock
    WalletService walletService;

    @InjectMocks
    WalletController controller;

    // ── withdraw ────────────────────────────────────────────────────────────

    @Test
    void withdraw_delegatesToServiceAndReturns200() {
        UUID playerId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("30.00");
        String key = "idem-abc";
        UUID txId = UUID.randomUUID();
        WithdrawalResponse expected = new WithdrawalResponse(txId, new BigDecimal("70.00"));

        when(walletService.withdraw(playerId, amount, key)).thenReturn(expected);

        WithdrawalRequest request = new WithdrawalRequest();
        request.setPlayerId(playerId);
        request.setAmount(amount);

        ResponseEntity<WithdrawalResponse> result = controller.withdraw(key, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(walletService).withdraw(playerId, amount, key);
    }

    // ── deposit ─────────────────────────────────────────────────────────────

    @Test
    void deposit_delegatesToServiceAndReturns200() {
        UUID playerId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.00");
        String key = "idem-dep-1";
        UUID txId = UUID.randomUUID();
        WithdrawalResponse expected = new WithdrawalResponse(txId, new BigDecimal("150.00"));

        when(walletService.deposit(playerId, amount, key)).thenReturn(expected);

        WithdrawalRequest request = new WithdrawalRequest();
        request.setPlayerId(playerId);
        request.setAmount(amount);

        ResponseEntity<WithdrawalResponse> result = controller.deposit(key, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(walletService).deposit(playerId, amount, key);
    }

    // ── getBalance ──────────────────────────────────────────────────────────

    @Test
    void getBalance_delegatesToServiceAndReturns200() {
        UUID playerId = UUID.randomUUID();
        BigDecimal balance = new BigDecimal("100.00");

        when(walletService.getBalance(playerId)).thenReturn(balance);

        ResponseEntity<BigDecimal> result = controller.getBalance(playerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualByComparingTo("100.00");
        verify(walletService).getBalance(playerId);
    }
}
