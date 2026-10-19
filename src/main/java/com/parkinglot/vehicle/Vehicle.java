package com.parkinglot.vehicle;

import com.parkinglot.enums.VehicleType;

import java.util.Objects;

public abstract class Vehicle {
    private final String licensePlate;
    private final VehicleType type;

    protected Vehicle(String licensePlate, VehicleType type) {
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException(
                "License plate cannot be empty");
        }
        this.licensePlate = licensePlate;
        this.type = Objects.requireNonNull(type);
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getType() {
        return type;
    }
}
