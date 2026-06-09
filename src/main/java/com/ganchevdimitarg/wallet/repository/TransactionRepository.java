package com.ganchevdimitarg.wallet.repository;

import com.ganchevdimitarg.wallet.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Page<Transaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    // Used to detect duplicate idempotency keys at the DB level (belt-and-suspenders)
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
