package com.parkinglot;

import com.parkinglot.enums.SpotType;
import com.parkinglot.enums.TicketStatus;
import com.parkinglot.enums.VehicleType;
import com.parkinglot.exception.ParkingLotFullException;
import com.parkinglot.exception.VehicleAlreadyParkedException;
import com.parkinglot.lot.ParkingFloor;
import com.parkinglot.lot.ParkingLot;
import com.parkinglot.payment.MockPaymentService;
import com.parkinglot.service.ParkingLotService;
import com.parkinglot.spot.ParkingSpot;
import com.parkinglot.strategy.FirstAvailableSpotStrategy;
import com.parkinglot.strategy.HourlyPricingStrategy;
import com.parkinglot.ticket.ParkingTicket;
import com.parkinglot.vehicle.Car;
import com.parkinglot.vehicle.Motorcycle;
import com.parkinglot.vehicle.Truck;
import com.parkinglot.vehicle.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Runnable checks for core parking-lot behavior (no external test framework).
 */
public final class ParkingLotVerification {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        checkSpotFitRules();
        checkHourlyPricing();
        checkVehicleValidation();
        checkParkAndExitHappyPath();
        checkDuplicateParkRejected();
        checkLotFullForTruck();
        checkPaymentFailureKeepsSpot();
        checkDoubleExitRejected();
        checkUnknownTicketRejected();

        System.out.println("Verification: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static ParkingLotService newService(ParkingLot lot, MockPaymentService pay) {
        Map<VehicleType, BigDecimal> rates = Map.of(
            VehicleType.MOTORCYCLE, new BigDecimal("20.00"),
            VehicleType.CAR, new BigDecimal("50.00"),
            VehicleType.TRUCK, new BigDecimal("100.00")
        );
        return new ParkingLotService(
            lot,
            new FirstAvailableSpotStrategy(),
            new HourlyPricingStrategy(rates),
            pay
        );
    }

    private static ParkingLot lotWithGroundSpots() {
        ParkingLot lot = new ParkingLot("TEST-01");
        ParkingFloor ground = new ParkingFloor("G");
        ground.addSpot(new ParkingSpot("G-M1", SpotType.MOTORCYCLE));
        ground.addSpot(new ParkingSpot("G-C1", SpotType.COMPACT));
        ground.addSpot(new ParkingSpot("G-L1", SpotType.LARGE));
        lot.addFloor(ground);
        return lot;
    }

    private static void checkSpotFitRules() {
        ParkingSpot m = new ParkingSpot("M", SpotType.MOTORCYCLE);
        ParkingSpot c = new ParkingSpot("C", SpotType.COMPACT);
        ParkingSpot l = new ParkingSpot("L", SpotType.LARGE);

        assertTrue("motorcycle fits motorcycle spot", m.canFit(new Motorcycle("X1")));
        assertTrue("motorcycle fits compact spot", c.canFit(new Motorcycle("X2")));
        assertTrue("car fits compact", c.canFit(new Car("C1")));
        assertTrue("car fits large", l.canFit(new Car("C2")));
        assertFalse("car cannot fit motorcycle-only spot", m.canFit(new Car("C3")));
        assertTrue("truck fits large", l.canFit(new Truck("T1")));
        assertFalse("truck cannot fit compact", c.canFit(new Truck("T2")));
    }

    private static void checkHourlyPricing() {
        HourlyPricingStrategy pricing = new HourlyPricingStrategy(
            Map.of(VehicleType.CAR, new BigDecimal("50.00"))
        );
        Instant entry = Instant.parse("2026-01-01T10:00:00Z");

        BigDecimal zeroDuration = pricing.calculateFee(
            new Car("P1"), entry, entry.plusSeconds(30));
        assertEquals("minimum one hour charge", new BigDecimal("50.00"), zeroDuration);

        BigDecimal justOverHour = pricing.calculateFee(
            new Car("P2"), entry, entry.plusSeconds(3601));
        assertEquals("second started hour billed", new BigDecimal("100.00"), justOverHour);

        try {
            pricing.calculateFee(new Car("P3"), entry, entry.minusSeconds(1));
            fail("exit before entry should throw");
        } catch (IllegalArgumentException expected) {
            pass("exit before entry rejected");
        }
    }

    private static void checkVehicleValidation() {
        try {
            new Car("  ");
            fail("blank plate should throw");
        } catch (IllegalArgumentException expected) {
            pass("blank plate rejected");
        }
    }

    private static void checkParkAndExitHappyPath() {
        MockPaymentService pay = new MockPaymentService();
        ParkingLotService service = newService(lotWithGroundSpots(), pay);

        ParkingTicket ticket = service.park(new Car("CAR-1"));
        assertEquals("car assigned compact first", "G-C1", ticket.getSpot().getId());
        assertEquals("ticket active after park", TicketStatus.ACTIVE, ticket.getStatus());

        BigDecimal fee = service.exit(ticket.getId());
        assertEquals("one hour car fee", new BigDecimal("50.00"), fee);
        assertEquals("ticket closed", TicketStatus.CLOSED, ticket.getStatus());
        assertEquals("no active tickets", 0, service.activeTicketCount());
    }

    private static void checkDuplicateParkRejected() {
        MockPaymentService pay = new MockPaymentService();
        ParkingLotService service = newService(lotWithGroundSpots(), pay);
        service.park(new Car("DUP-1"));

        try {
            service.park(new Car("DUP-1"));
            fail("duplicate plate should throw");
        } catch (VehicleAlreadyParkedException expected) {
            pass("duplicate park rejected");
        }
    }

    private static void checkLotFullForTruck() {
        ParkingLot lot = new ParkingLot("FULL-01");
        ParkingFloor f = new ParkingFloor("G");
        f.addSpot(new ParkingSpot("C1", SpotType.COMPACT));
        lot.addFloor(f);

        MockPaymentService pay = new MockPaymentService();
        ParkingLotService service = newService(lot, pay);

        try {
            service.park(new Truck("TRK-1"));
            fail("truck with no large spot should throw");
        } catch (ParkingLotFullException expected) {
            pass("lot full for incompatible truck");
        }
    }

    private static void checkPaymentFailureKeepsSpot() {
        MockPaymentService pay = new MockPaymentService();
        pay.setShouldFail(true);
        ParkingLotService service = newService(lotWithGroundSpots(), pay);

        ParkingTicket ticket = service.park(new Car("PAY-FAIL"));
        String spotId = ticket.getSpot().getId();

        try {
            service.exit(ticket.getId());
            fail("failed payment should throw");
        } catch (IllegalStateException expected) {
            pass("payment failure surfaces");
        }

        assertEquals("ticket active after failed payment", TicketStatus.ACTIVE, ticket.getStatus());
        assertEquals("vehicle still counted active", 1, service.activeTicketCount());
        assertFalse("spot still occupied", ticket.getSpot().isAvailable());

        pay.setShouldFail(false);
        service.exit(ticket.getId());
        assertTrue("spot free after successful retry exit", ticket.getSpot().isAvailable());
        assertEquals("spot id unchanged", spotId, ticket.getSpot().getId());
    }

    private static void checkDoubleExitRejected() {
        MockPaymentService pay = new MockPaymentService();
        ParkingLotService service = newService(lotWithGroundSpots(), pay);
        ParkingTicket ticket = service.park(new Car("EXIT-2X"));
        service.exit(ticket.getId());

        try {
            service.exit(ticket.getId());
            fail("second exit should throw");
        } catch (IllegalStateException expected) {
            pass("double exit rejected");
        }
    }

    private static void checkUnknownTicketRejected() {
        MockPaymentService pay = new MockPaymentService();
        ParkingLotService service = newService(lotWithGroundSpots(), pay);

        try {
            service.exit("NO-SUCH-TICKET");
            fail("unknown ticket should throw");
        } catch (IllegalArgumentException expected) {
            pass("unknown ticket rejected");
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            pass(name);
        } else {
            fail(name + " (expected true)");
        }
    }

    private static void assertFalse(String name, boolean condition) {
        assertTrue(name, !condition);
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            pass(name);
        } else {
            fail(name + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  OK: " + name);
    }

    private static void fail(String name) {
        failed++;
        System.err.println("  FAIL: " + name);
    }
}
