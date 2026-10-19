package com.parkinglot.strategy;

import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;

public interface PricingStrategy {
    BigDecimal calculateFee(
        Vehicle vehicle,
        Instant entryTime,
        Instant exitTime
    );
}
