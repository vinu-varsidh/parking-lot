package com.parkinglot.exception;

public final class VehicleAlreadyParkedException
        extends RuntimeException {
    public VehicleAlreadyParkedException(String message) {
        super(message);
    }
}
