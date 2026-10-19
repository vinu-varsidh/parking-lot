package com.parkinglot.strategy;

import com.parkinglot.enums.VehicleType;
import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class HourlyPricingStrategy implements PricingStrategy {
    private final Map<VehicleType, BigDecimal> hourlyRates;

    public HourlyPricingStrategy(
            Map<VehicleType, BigDecimal> hourlyRates) {
        this.hourlyRates = Map.copyOf(hourlyRates);
    }

    @Override
    public BigDecimal calculateFee(
            Vehicle vehicle,
            Instant entryTime,
            Instant exitTime) {

        if (exitTime.isBefore(entryTime)) {
            throw new IllegalArgumentException(
                "Exit cannot precede entry");
        }

        long seconds = Duration.between(
            entryTime, exitTime).getSeconds();

        // Charge for each started hour, with a minimum of one hour.
        long hours = Math.max(1, (seconds + 3599) / 3600);

        BigDecimal rate = hourlyRates.get(vehicle.getType());

        if (rate == null) {
            throw new IllegalArgumentException(
                "No rate configured for " + vehicle.getType());
        }

        return rate.multiply(BigDecimal.valueOf(hours))
            .setScale(2, RoundingMode.HALF_UP);
    }
}
