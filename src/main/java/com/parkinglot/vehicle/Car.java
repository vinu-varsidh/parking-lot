package com.parkinglot.vehicle;

import com.parkinglot.enums.VehicleType;

public final class Car extends Vehicle {
    public Car(String plate) {
        super(plate, VehicleType.CAR);
    }
}
