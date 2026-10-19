package com.parkinglot.ticket;

import com.parkinglot.enums.TicketStatus;
import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class ParkingTicket {
    private final String id;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final Instant entryTime;

    private TicketStatus status = TicketStatus.ACTIVE;
    private Instant exitTime;
    private BigDecimal fee;

    public ParkingTicket(
            String id,
            Vehicle vehicle,
            ParkingSpot spot,
            Instant entryTime) {

        this.id = Objects.requireNonNull(id);
        this.vehicle = Objects.requireNonNull(vehicle);
        this.spot = Objects.requireNonNull(spot);
        this.entryTime = Objects.requireNonNull(entryTime);
    }

    public String getId() {
        return id;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getSpot() {
        return spot;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public synchronized TicketStatus getStatus() {
        return status;
    }

    public synchronized BigDecimal getFee() {
        return fee;
    }

    public synchronized Instant getExitTime() {
        return exitTime;
    }

    public synchronized void beginPayment() {
        requireStatus(TicketStatus.ACTIVE);
        status = TicketStatus.PAYMENT_PENDING;
    }

    public synchronized void paymentFailed() {
        requireStatus(TicketStatus.PAYMENT_PENDING);
        status = TicketStatus.ACTIVE;
    }

    public synchronized void close(Instant exitTime, BigDecimal fee) {
        requireStatus(TicketStatus.PAYMENT_PENDING);
        this.exitTime = Objects.requireNonNull(exitTime);
        this.fee = Objects.requireNonNull(fee);
        status = TicketStatus.CLOSED;
    }

    private void requireStatus(TicketStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Expected " + expected + ", found " + status);
        }
    }
}
