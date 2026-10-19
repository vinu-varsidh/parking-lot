package com.parkinglot.spot;

import com.parkinglot.enums.SpotStatus;
import com.parkinglot.enums.SpotType;
import com.parkinglot.vehicle.Vehicle;

import java.util.Objects;

public final class ParkingSpot {
    private final String id;
    private final SpotType type;

    private SpotStatus status = SpotStatus.AVAILABLE;
    private Vehicle occupant;

    public ParkingSpot(String id, SpotType type) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
    }

    public String getId() {
        return id;
    }

    public SpotType getType() {
        return type;
    }

    public synchronized boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    public boolean canFit(Vehicle vehicle) {
        return switch (vehicle.getType()) {
            case MOTORCYCLE -> true;
            case CAR -> type == SpotType.COMPACT
                     || type == SpotType.LARGE;
            case TRUCK -> type == SpotType.LARGE;
        };
    }

    /** Atomic check-and-claim operation. */
    public synchronized boolean tryOccupy(Vehicle vehicle) {
        Objects.requireNonNull(vehicle);

        if (status != SpotStatus.AVAILABLE
                || !canFit(vehicle)) {
            return false;
        }

        occupant = vehicle;
        status = SpotStatus.OCCUPIED;
        return true;
    }

    public synchronized void release(String licensePlate) {
        if (status != SpotStatus.OCCUPIED
                || occupant == null
                || !occupant.getLicensePlate()
                    .equals(licensePlate)) {
            throw new IllegalStateException(
                "Spot is not occupied by this vehicle");
        }

        occupant = null;
        status = SpotStatus.AVAILABLE;
    }
}
