package com.parkinglot.lot;

import com.parkinglot.spot.ParkingSpot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ParkingLot {
    private final String id;
    private final List<ParkingFloor> floors = new ArrayList<>();

    public ParkingLot(String id) {
        this.id = Objects.requireNonNull(id);
    }

    public String getId() {
        return id;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(Objects.requireNonNull(floor));
    }

    public List<ParkingSpot> getAllSpots() {
        List<ParkingSpot> result = new ArrayList<>();

        for (ParkingFloor floor : floors) {
            result.addAll(floor.getSpots());
        }

        return Collections.unmodifiableList(result);
    }
}
