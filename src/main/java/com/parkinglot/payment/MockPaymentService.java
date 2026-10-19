package com.parkinglot.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** Demo-only payment implementation. */
public final class MockPaymentService implements PaymentService {
    private boolean shouldFail;

    public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
    }

    @Override
    public PaymentResult pay(
            String ticketId, BigDecimal amount) {

        if (shouldFail) {
            return PaymentResult.failure();
        }

        return PaymentResult.success(
            "PAY-" + UUID.randomUUID());
    }
}
