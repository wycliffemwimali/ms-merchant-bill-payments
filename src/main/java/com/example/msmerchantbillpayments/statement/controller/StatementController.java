package com.example.msmerchantbillpayments.statement.controller;

import com.example.msmerchantbillpayments.statement.dto.MiniStatementResponse;
import com.example.msmerchantbillpayments.statement.service.StatementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class StatementController {
    private static final Logger log = LoggerFactory.getLogger(StatementController.class);
    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @GetMapping("/{accountNumber}/mini-statement")
    public MiniStatementResponse getMiniStatement(@PathVariable String accountNumber) {
        MiniStatementResponse response = service.getMiniStatement(accountNumber);
        log.info("Customer mini-statement retrieved");
        return response;
    }
}
