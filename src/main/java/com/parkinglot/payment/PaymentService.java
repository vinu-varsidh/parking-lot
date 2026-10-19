package com.parkinglot.payment;

import java.math.BigDecimal;

public interface PaymentService {
    PaymentResult pay(String ticketId, BigDecimal amount);
}
