package com.ganchevdimitarg.wallet.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(BigDecimal balance, BigDecimal requested) {
        super(String.format(
            "Insufficient funds: balance is %s, requested %s", balance, requested));
    }
}
