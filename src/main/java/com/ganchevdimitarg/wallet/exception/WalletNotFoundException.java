package com.ganchevdimitarg.wallet.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(UUID playerId) {
        super("Wallet not found for player: " + playerId);
    }
}
