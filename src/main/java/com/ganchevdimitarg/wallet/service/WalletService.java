package com.ganchevdimitarg.wallet.service;

import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.exception.InsufficientFundsException;
import com.ganchevdimitarg.wallet.exception.ServiceUnavailableException;
import com.ganchevdimitarg.wallet.exception.WalletNotFoundException;
import com.ganchevdimitarg.wallet.model.Transaction;
import com.ganchevdimitarg.wallet.model.TransactionType;
import com.ganchevdimitarg.wallet.model.Wallet;
import com.ganchevdimitarg.wallet.repository.TransactionRepository;
import com.ganchevdimitarg.wallet.repository.WalletRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate redis;

    private static final Duration BALANCE_TTL = Duration.ofSeconds(5);
    private static final String BALANCE_KEY = "balance:";

    // ── Withdraw ─────────────────────────────────────────────────────────────

    @Transactional
    @CircuitBreaker(name = "wallet-db", fallbackMethod = "withdrawFallback")
    public WithdrawalResponse withdraw(UUID playerId,
                                       BigDecimal amount,
                                       String idempotencyKey) {

        // DB-level idempotency guard — catches duplicates if Redis was down
        Optional<Transaction> existing = transactionRepository
                .findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Wallet wallet = walletRepository.findByPlayerId(playerId)
                    .orElseThrow(() -> new WalletNotFoundException(playerId));
            return new WithdrawalResponse(existing.get().getId(), wallet.getBalance());
        }

        // Pessimistic lock — blocks concurrent withdrawals on the same wallet row
        Wallet wallet = walletRepository.findByPlayerIdWithLock(playerId)
                .orElseThrow(() -> new WalletNotFoundException(playerId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(wallet.getBalance(), amount);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));
        walletRepository.save(wallet);

        Transaction tx = Transaction.builder()
                .walletId(wallet.getId())
                .amount(amount.negate())
                .type(TransactionType.WITHDRAWAL)
                .idempotencyKey(idempotencyKey)
                .build();
        transactionRepository.save(tx);

        // Invalidate cache — stale balance after a withdrawal is unacceptable
        evictBalanceCache(playerId);

        return new WithdrawalResponse(tx.getId(), wallet.getBalance());
    }

    public WithdrawalResponse withdrawFallback(UUID playerId,
                                               BigDecimal amount,
                                               String idempotencyKey,
                                               Exception ex) {
        log.error("Circuit open for withdraw — playerId={} reason={}", playerId, ex.getMessage());
        throw new ServiceUnavailableException("Withdrawal service temporarily unavailable");
    }

    // ── Deposit ──────────────────────────────────────────────────────────────

    @Transactional
    @CircuitBreaker(name = "wallet-db", fallbackMethod = "depositFallback")
    public WithdrawalResponse deposit(UUID playerId,
                                      BigDecimal amount,
                                      String idempotencyKey) {

        Optional<Transaction> existing = transactionRepository
                .findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Wallet wallet = walletRepository.findByPlayerId(playerId)
                    .orElseThrow(() -> new WalletNotFoundException(playerId));
            return new WithdrawalResponse(existing.get().getId(), wallet.getBalance());
        }

        Wallet wallet = walletRepository.findByPlayerIdWithLock(playerId)
                .orElseThrow(() -> new WalletNotFoundException(playerId));

        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        Transaction tx = Transaction.builder()
                .walletId(wallet.getId())
                .amount(amount)
                .type(TransactionType.DEPOSIT)
                .idempotencyKey(idempotencyKey)
                .build();
        transactionRepository.save(tx);

        evictBalanceCache(playerId);

        return new WithdrawalResponse(tx.getId(), wallet.getBalance());
    }

    public WithdrawalResponse depositFallback(UUID playerId,
                                              BigDecimal amount,
                                              String idempotencyKey,
                                              Exception ex) {
        log.error("Circuit open for deposit — playerId={} reason={}", playerId, ex.getMessage());
        throw new ServiceUnavailableException("Deposit service temporarily unavailable");
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)   // routed to read replica via DataSourceConfig
    @CircuitBreaker(name = "wallet-db", fallbackMethod = "getBalanceFallback")
    public BigDecimal getBalance(UUID playerId) {
        // Cache miss — hit the read replica
        String cached = redis.opsForValue().get(BALANCE_KEY + playerId);
        if (cached != null) {
            log.debug("Balance cache hit for playerId={}", playerId);
            return new BigDecimal(cached);
        }

        BigDecimal balance = walletRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new WalletNotFoundException(playerId))
                .getBalance();

        redis.opsForValue().set(BALANCE_KEY + playerId, balance.toPlainString(), BALANCE_TTL);
        return balance;
    }

    // Fallback: serve stale cache when DB is down rather than returning 503
    public BigDecimal getBalanceFallback(UUID playerId, Exception ex) {
        log.warn("Circuit open for getBalance — serving stale cache for playerId={}", playerId);
        String cached = redis.opsForValue().get(BALANCE_KEY + playerId);
        if (cached != null) return new BigDecimal(cached);
        throw new ServiceUnavailableException("Balance service temporarily unavailable");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void evictBalanceCache(UUID playerId) {
        redis.delete(BALANCE_KEY + playerId);
        log.debug("Balance cache evicted for playerId={}", playerId);
    }
}