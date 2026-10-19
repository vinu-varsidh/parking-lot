package com.parkinglot.vehicle;

import com.parkinglot.enums.VehicleType;

public final class Truck extends Vehicle {
    public Truck(String plate) {
        super(plate, VehicleType.TRUCK);
    }
}
