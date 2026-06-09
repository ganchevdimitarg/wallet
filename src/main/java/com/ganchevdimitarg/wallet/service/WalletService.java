package com.ganchevdimitarg.wallet.service;

import com.ganchevdimitarg.wallet.dto.WithdrawalRequest;
import com.ganchevdimitarg.wallet.dto.WithdrawalResponse;
import com.ganchevdimitarg.wallet.exception.InsufficientFundsException;
import com.ganchevdimitarg.wallet.exception.WalletNotFoundException;
import com.ganchevdimitarg.wallet.model.Transaction;
import com.ganchevdimitarg.wallet.model.TransactionType;
import com.ganchevdimitarg.wallet.model.Wallet;
import com.ganchevdimitarg.wallet.repository.TransactionRepository;
import com.ganchevdimitarg.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Withdraw amount from the player's wallet.
     *
     * The idempotencyKey is stored in the same DB transaction as the balance update.
     * This guarantees that either both succeed or both fail — no window where the
     * money moves but the key wasn't recorded (or vice-versa).
     */
    @Transactional
    public WithdrawalResponse withdraw(UUID playerId,
                                       BigDecimal amount,
                                       String idempotencyKey) {

        // Belt-and-suspenders: if the key is already in the DB, return the same result
        // (the Redis filter handles the fast path; this catches Redis failures)
        Optional<Transaction> existing = transactionRepository
            .findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transaction tx = existing.get();
            Wallet wallet = walletRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new WalletNotFoundException(playerId));
            return new WithdrawalResponse(tx.getId(), wallet.getBalance());
        }

        // Acquire a row-level lock — blocks other transactions on this wallet row
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
            .idempotencyKey(idempotencyKey)  // stored atomically with the balance update
            .build();
        transactionRepository.save(tx);

        return new WithdrawalResponse(tx.getId(), wallet.getBalance());
    }

    @Transactional
    public WithdrawalResponse deposit(UUID playerId,
                                      BigDecimal amount,
                                      String idempotencyKey) {

        Optional<Transaction> existing = transactionRepository
            .findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Transaction tx = existing.get();
            Wallet wallet = walletRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new WalletNotFoundException(playerId));
            return new WithdrawalResponse(tx.getId(), wallet.getBalance());
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

        return new WithdrawalResponse(tx.getId(), wallet.getBalance());
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID playerId) {
        return walletRepository.findByPlayerId(playerId)
            .orElseThrow(() -> new WalletNotFoundException(playerId))
            .getBalance();
    }
}
