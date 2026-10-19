package com.parkinglot.payment;

public record PaymentResult(boolean successful, String reference) {
    public static PaymentResult success(String reference) {
        return new PaymentResult(true, reference);
    }

    public static PaymentResult failure() {
        return new PaymentResult(false, null);
    }
}
