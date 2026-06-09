package com.ganchevdimitarg.wallet.repository;

import com.ganchevdimitarg.wallet.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    // Acquires SELECT ... FOR UPDATE — blocks concurrent writes on the same row
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.playerId = :playerId")
    Optional<Wallet> findByPlayerIdWithLock(@Param("playerId") UUID playerId);

    // Read-only — no lock needed, used for balance checks that don't mutate
    Optional<Wallet> findByPlayerId(UUID playerId);
}
