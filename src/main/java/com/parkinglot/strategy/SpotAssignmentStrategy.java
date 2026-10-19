package com.parkinglot.strategy;

import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.vehicle.Vehicle;

import java.util.List;
import java.util.Optional;

public interface SpotAssignmentStrategy {
    Optional<ParkingSpot> findSpot(
        Vehicle vehicle,
        List<ParkingSpot> spots
    );
}
