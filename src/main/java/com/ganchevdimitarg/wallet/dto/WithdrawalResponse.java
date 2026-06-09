package com.ganchevdimitarg.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@AllArgsConstructor
public class WithdrawalResponse {
    private UUID transactionId;
    private BigDecimal balanceAfter;
}
