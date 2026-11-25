package com.example.msmerchantbillpayments.payment.controller;

import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentResponse;
import com.example.msmerchantbillpayments.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<BillPaymentResponse> pay(@Valid @RequestBody BillPaymentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        BillPaymentResponse response = service.pay(request, idempotencyKey);
        log.info("Bill payment completed: reference={}", response.reference());
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }
}
