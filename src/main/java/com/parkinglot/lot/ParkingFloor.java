package com.parkinglot.lot;

import com.parkinglot.spot.ParkingSpot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ParkingFloor {
    private final String id;
    private final List<ParkingSpot> spots = new ArrayList<>();

    public ParkingFloor(String id) {
        this.id = Objects.requireNonNull(id);
    }

    public String getId() {
        return id;
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(Objects.requireNonNull(spot));
    }

    public List<ParkingSpot> getSpots() {
        return Collections.unmodifiableList(spots);
    }
}
