package com.parkinglot;

import com.parkinglot.enums.SpotType;
import com.parkinglot.enums.VehicleType;
import com.parkinglot.lot.ParkingFloor;
import com.parkinglot.lot.ParkingLot;
import com.parkinglot.payment.MockPaymentService;
import com.parkinglot.service.ParkingLotService;
import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.strategy.FirstAvailableSpotStrategy;
import com.parkinglot.strategy.HourlyPricingStrategy;
import com.parkinglot.ticket.ParkingTicket;
import com.parkinglot.vehicle.Car;
import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.util.Map;

public class ParkingLotDemo {

    public static void main(String[] args) {
        ParkingLot lot = new ParkingLot("CHENNAI-01");

        ParkingFloor ground = new ParkingFloor("G");

        ground.addSpot(new ParkingSpot("G-M1",
            SpotType.MOTORCYCLE));
        ground.addSpot(new ParkingSpot("G-C1",
            SpotType.COMPACT));
        ground.addSpot(new ParkingSpot("G-L1",
            SpotType.LARGE));

        lot.addFloor(ground);

        Map<VehicleType, BigDecimal> rates = Map.of(
            VehicleType.MOTORCYCLE, new BigDecimal("20.00"),
            VehicleType.CAR, new BigDecimal("50.00"),
            VehicleType.TRUCK, new BigDecimal("100.00")
        );

        MockPaymentService payments = new MockPaymentService();

        ParkingLotService service = new ParkingLotService(
            lot,
            new FirstAvailableSpotStrategy(),
            new HourlyPricingStrategy(rates),
            payments
        );

        Vehicle car = new Car("TN01AB1234");

        ParkingTicket ticket = service.park(car);

        System.out.println("Ticket: " + ticket.getId());
        System.out.println("Spot: " + ticket.getSpot().getId());
        System.out.println("Status: " + ticket.getStatus());

        BigDecimal fee = service.exit(ticket.getId());

        System.out.println("Fee: Rs. " + fee);
        System.out.println("Status: " + ticket.getStatus());
        System.out.println("Active tickets: "
            + service.activeTicketCount());
    }
}
