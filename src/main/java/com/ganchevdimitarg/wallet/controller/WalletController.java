package com.ganchevdimitarg.wallet.controller;

import com.ganchevdimitarg.wallet.dto.WithdrawalRequest;
import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid WithdrawalRequest request) {

        WithdrawalResponse result = walletService.withdraw(
            request.getPlayerId(),
            request.getAmount(),
            idempotencyKey
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deposit")
    public ResponseEntity<WithdrawalResponse> deposit(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid WithdrawalRequest request) {

        WithdrawalResponse result = walletService.deposit(
            request.getPlayerId(),
            request.getAmount(),
            idempotencyKey
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{playerId}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable UUID playerId) {
        return ResponseEntity.ok(walletService.getBalance(playerId));
    }
}
