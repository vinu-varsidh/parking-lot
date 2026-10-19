package com.parkinglot.strategy;

import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.vehicle.Vehicle;

import java.util.List;
import java.util.Optional;

public final class FirstAvailableSpotStrategy
        implements SpotAssignmentStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(
            Vehicle vehicle,
            List<ParkingSpot> spots) {

        return spots.stream()
            .filter(ParkingSpot::isAvailable)
            .filter(spot -> spot.canFit(vehicle))
            .findFirst();
    }
}
