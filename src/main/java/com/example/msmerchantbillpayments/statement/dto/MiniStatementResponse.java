package com.example.msmerchantbillpayments.statement.dto;

import java.math.BigDecimal;
import java.util.List;

public record MiniStatementResponse(String accountNumber, String name, BigDecimal availableBalance,
                                    String currency, List<StatementTransaction> transactions) {
}
