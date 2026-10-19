package com.parkinglot.service;

import com.parkinglot.enums.TicketStatus;
import com.parkinglot.exception.ParkingLotFullException;
import com.parkinglot.exception.VehicleAlreadyParkedException;
import com.parkinglot.lot.ParkingLot;
import com.parkinglot.payment.PaymentResult;
import com.parkinglot.payment.PaymentService;
import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.strategy.PricingStrategy;
import com.parkinglot.strategy.SpotAssignmentStrategy;
import com.parkinglot.ticket.ParkingTicket;
import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class ParkingLotService {
    private final ParkingLot lot;
    private final SpotAssignmentStrategy assignmentStrategy;
    private final PricingStrategy pricingStrategy;
    private final PaymentService paymentService;

    private final Map<String, ParkingTicket> tickets =
        new HashMap<>();

    private final Map<String, ParkingTicket> activeByPlate =
        new HashMap<>();

    private final AtomicLong ticketSequence = new AtomicLong();

    /*
     * Baseline synchronization policy:
     * one JVM-wide critical section per service instance.
     *
     * This protects activeByPlate, tickets, spot assignment,
     * ticket transitions, payment outcome, and spot release.
     */
    public ParkingLotService(
            ParkingLot lot,
            SpotAssignmentStrategy assignmentStrategy,
            PricingStrategy pricingStrategy,
            PaymentService paymentService) {

        this.lot = Objects.requireNonNull(lot);
        this.assignmentStrategy =
            Objects.requireNonNull(assignmentStrategy);
        this.pricingStrategy =
            Objects.requireNonNull(pricingStrategy);
        this.paymentService =
            Objects.requireNonNull(paymentService);
    }

    public synchronized ParkingTicket park(Vehicle vehicle) {
        Objects.requireNonNull(vehicle);

        String plate = vehicle.getLicensePlate();

        if (activeByPlate.containsKey(plate)) {
            throw new VehicleAlreadyParkedException(
                "Vehicle already has an active ticket: " + plate);
        }

        List<ParkingSpot> spots = lot.getAllSpots();

        ParkingSpot spot = assignmentStrategy
            .findSpot(vehicle, spots)
            .orElseThrow(() -> new ParkingLotFullException(
                "No compatible spot available for " + plate));

        /*
         * Selection is not reservation. Claim atomically and
         * handle a lost race by trying another candidate.
         * The service lock also serializes this service's park calls.
         */
        if (!spot.tryOccupy(vehicle)) {
            throw new IllegalStateException(
                "Selected spot was no longer available");
        }

        String ticketId =
            lot.getId() + "-T" + ticketSequence.incrementAndGet();

        ParkingTicket ticket = new ParkingTicket(
            ticketId, vehicle, spot, Instant.now());

        tickets.put(ticketId, ticket);
        activeByPlate.put(plate, ticket);

        return ticket;
    }

    public synchronized BigDecimal exit(String ticketId) {
        ParkingTicket ticket = tickets.get(ticketId);

        if (ticket == null) {
            throw new IllegalArgumentException(
                "Unknown ticket: " + ticketId);
        }

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new IllegalStateException(
                "Ticket already closed: " + ticketId);
        }

        ticket.beginPayment();

        Instant exitTime = Instant.now();
        BigDecimal fee = pricingStrategy.calculateFee(
            ticket.getVehicle(),
            ticket.getEntryTime(),
            exitTime);

        PaymentResult result;

        try {
            result = paymentService.pay(ticketId, fee);
        } catch (RuntimeException ex) {
            ticket.paymentFailed();
            throw ex;
        }

        if (result == null || !result.successful()) {
            ticket.paymentFailed();
            throw new IllegalStateException(
                "Payment failed; vehicle remains parked");
        }

        /*
         * Payment succeeded. Release the spot and close the ticket.
         * The service lock prevents another service operation from
         * interleaving between these steps.
         */
        ticket.getSpot().release(
            ticket.getVehicle().getLicensePlate());

        ticket.close(exitTime, fee);
        activeByPlate.remove(
            ticket.getVehicle().getLicensePlate());

        return fee;
    }

    public synchronized Optional<ParkingTicket> findTicket(
            String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    public synchronized int activeTicketCount() {
        return activeByPlate.size();
    }
}
