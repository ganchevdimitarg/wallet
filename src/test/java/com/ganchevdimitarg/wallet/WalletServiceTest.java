package com.ganchevdimitarg.wallet;

import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.exception.InsufficientFundsException;
import com.ganchevdimitarg.wallet.exception.ServiceUnavailableException;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
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
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

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
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
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

    // ── Deposit ─────────────────────────────────────────────────────────────

    @Test
    void deposit_successfullyAddsBalance() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty());
        when(walletRepository.findByPlayerIdWithLock(playerId))
                .thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        WithdrawalResponse response = walletService.deposit(
                playerId, new BigDecimal("50.00"), idempotencyKey);

        assertThat(response.getBalanceAfter()).isEqualByComparingTo("150.00");
        verify(walletRepository).save(wallet);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void deposit_idempotencyKeyAlreadyExists_returnsCachedResult() {
        UUID existingTxId = UUID.randomUUID();
        Transaction existingTx = Transaction.builder()
                .id(existingTxId)
                .walletId(walletId)
                .amount(new BigDecimal("50.00"))
                .type(TransactionType.DEPOSIT)
                .idempotencyKey(idempotencyKey)
                .build();

        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(existingTx));
        when(walletRepository.findByPlayerId(playerId))
                .thenReturn(Optional.of(wallet));

        WithdrawalResponse response = walletService.deposit(
                playerId, new BigDecimal("50.00"), idempotencyKey);

        assertThat(response.getTransactionId()).isEqualTo(existingTxId);
        assertThat(response.getBalanceAfter()).isEqualByComparingTo("100.00");
        verify(walletRepository, never()).findByPlayerIdWithLock(any());
        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void deposit_walletNotFound_throwsException() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty());
        when(walletRepository.findByPlayerIdWithLock(playerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                walletService.deposit(playerId, new BigDecimal("50.00"), idempotencyKey))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // ── getBalance ──────────────────────────────────────────────────────────

    @Test
    void getBalance_cacheHit_returnsCachedValue() {
        when(valueOps.get("balance:" + playerId))
                .thenReturn("75.50");

        BigDecimal balance = walletService.getBalance(playerId);

        assertThat(balance).isEqualByComparingTo("75.50");
        // Must NOT hit the DB when cache is populated
        verify(walletRepository, never()).findByPlayerId(any());
    }

    @Test
    void getBalance_cacheMiss_hitsDatabaseAndCachesResult() {
        when(valueOps.get("balance:" + playerId))
                .thenReturn(null);
        when(walletRepository.findByPlayerId(playerId))
                .thenReturn(Optional.of(wallet));

        BigDecimal balance = walletService.getBalance(playerId);

        assertThat(balance).isEqualByComparingTo("100.00");
        verify(walletRepository).findByPlayerId(playerId);
        verify(valueOps).set("balance:" + playerId, "100.00",
                Duration.ofSeconds(5));
    }

    @Test
    void getBalance_walletNotFound_throwsException() {
        when(valueOps.get("balance:" + playerId))
                .thenReturn(null);
        when(walletRepository.findByPlayerId(playerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getBalance(playerId))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void getBalanceFallback_servesStaleCache() {
        when(valueOps.get("balance:" + playerId))
                .thenReturn("42.00");

        BigDecimal balance = walletService.getBalanceFallback(
                playerId, new RuntimeException("DB down"));

        assertThat(balance).isEqualByComparingTo("42.00");
    }

    @Test
    void getBalanceFallback_throwsWhenNoCachedData() {
        when(valueOps.get("balance:" + playerId))
                .thenReturn(null);

        assertThatThrownBy(() ->
                walletService.getBalanceFallback(playerId, new RuntimeException("DB down")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Balance service temporarily unavailable");
    }

    // ── Circuit breaker fallbacks ───────────────────────────────────────────

    @Test
    void withdrawFallback_throwsServiceUnavailable() {
        assertThatThrownBy(() ->
                walletService.withdrawFallback(playerId, new BigDecimal("10.00"),
                        idempotencyKey, new RuntimeException("DB down")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Withdrawal service temporarily unavailable");
    }

    @Test
    void depositFallback_throwsServiceUnavailable() {
        assertThatThrownBy(() ->
                walletService.depositFallback(playerId, new BigDecimal("10.00"),
                        idempotencyKey, new RuntimeException("DB down")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Deposit service temporarily unavailable");
    }
}
