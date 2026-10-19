package com.parkinglot.exception;

public final class ParkingLotFullException
        extends RuntimeException {
    public ParkingLotFullException(String message) {
        super(message);
    }
}
