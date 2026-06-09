package com.ganchevdimitarg.wallet;

import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.exception.InsufficientFundsException;
import com.ganchevdimitarg.wallet.exception.WalletNotFoundException;
import com.ganchevdimitarg.wallet.model.Transaction;
import com.ganchevdimitarg.wallet.model.TransactionType;
import com.ganchevdimitarg.wallet.model.Wallet;
import com.ganchevdimitarg.wallet.repository.TransactionRepository;
import com.ganchevdimitarg.wallet.repository.WalletRepository;
import com.ganchevdimitarg.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    WalletRepository walletRepository;
    @Mock
    TransactionRepository transactionRepository;

    @InjectMocks
    WalletService walletService;

    private UUID playerId;
    private UUID walletId;
    private String idempotencyKey;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        playerId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
        wallet = Wallet.builder()
            .id(walletId)
            .playerId(playerId)
            .balance(new BigDecimal("100.00"))
            .version(0L)
            .build();
    }

    @Test
    void withdraw_successfullyDeductsBalance() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(walletRepository.findByPlayerIdWithLock(playerId))
            .thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any()))
            .thenAnswer(inv -> inv.getArgument(0));

        WithdrawalResponse response = walletService.withdraw(
            playerId, new BigDecimal("30.00"), idempotencyKey);

        assertThat(response.getBalanceAfter()).isEqualByComparingTo("70.00");
        verify(walletRepository).save(wallet);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void withdraw_throwsInsufficientFunds_whenBalanceTooLow() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(walletRepository.findByPlayerIdWithLock(playerId))
            .thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
            walletService.withdraw(playerId, new BigDecimal("200.00"), idempotencyKey))
            .isInstanceOf(InsufficientFundsException.class)
            .hasMessageContaining("100.00")
            .hasMessageContaining("200.00");

        // Balance must not change and no transaction must be saved
        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_throwsWalletNotFound_whenPlayerHasNoWallet() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.empty());
        when(walletRepository.findByPlayerIdWithLock(playerId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            walletService.withdraw(playerId, new BigDecimal("10.00"), idempotencyKey))
            .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void withdraw_returnsCachedResult_whenIdempotencyKeyAlreadyExists() {
        UUID existingTxId = UUID.randomUUID();
        Transaction existingTx = Transaction.builder()
            .id(existingTxId)
            .walletId(walletId)
            .amount(new BigDecimal("-30.00"))
            .type(TransactionType.WITHDRAWAL)
            .idempotencyKey(idempotencyKey)
            .build();

        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
            .thenReturn(Optional.of(existingTx));
        when(walletRepository.findByPlayerId(playerId))
            .thenReturn(Optional.of(wallet));

        WithdrawalResponse response = walletService.withdraw(
            playerId, new BigDecimal("30.00"), idempotencyKey);

        assertThat(response.getTransactionId()).isEqualTo(existingTxId);
        // The wallet and transaction must NOT be mutated on a duplicate request
        verify(walletRepository, never()).findByPlayerIdWithLock(any());
        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }
}
