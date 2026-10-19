package com.parkinglot.vehicle;

import com.parkinglot.enums.VehicleType;

public final class Motorcycle extends Vehicle {
    public Motorcycle(String plate) {
        super(plate, VehicleType.MOTORCYCLE);
    }
}
