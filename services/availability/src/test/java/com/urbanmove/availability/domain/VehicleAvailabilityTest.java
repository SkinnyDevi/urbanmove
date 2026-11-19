package com.urbanmove.availability.domain;

import static com.urbanmove.availability.domain.VehicleAction.*;
import static com.urbanmove.availability.domain.VehicleStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VehicleAvailabilityTest {

    private static final VehicleId VEHICLE = new VehicleId("veh-0042");
    private static final ZoneId TAFIRA = new ZoneId("tafira");
    private static final ZoneId MESA = new ZoneId("mesa-y-lopez");

    /**
     * Matriz de transiciones esperada: es la misma tabla que docs/architecture/vehicle-state-machine.md.
     * Lo que no aparece aquí es ilegal y debe lanzar InvalidStateTransitionError.
     */
    private static final Map<VehicleStatus, Map<VehicleAction, VehicleStatus>> ALLOWED = new EnumMap<>(VehicleStatus.class);

    static {
        allow(AVAILABLE, RESERVE, RESERVED);
        allow(AVAILABLE, START_RIDE, IN_USE);
        allow(AVAILABLE, REPORT_LOW_BATTERY, LOW_BATTERY);
        allow(AVAILABLE, WITHDRAW_FOR_MAINTENANCE, WITHDRAWN);
        allow(AVAILABLE, RELOCATE, AVAILABLE);

        allow(RESERVED, RELEASE_RESERVATION, AVAILABLE);
        allow(RESERVED, START_RIDE, IN_USE);
        allow(RESERVED, REPORT_LOW_BATTERY, RESERVED);

        allow(IN_USE, FINISH_RIDE, AVAILABLE);
        allow(IN_USE, REPORT_LOW_BATTERY, IN_USE);

        allow(LOW_BATTERY, REPORT_LOW_BATTERY, LOW_BATTERY);
        allow(LOW_BATTERY, WITHDRAW_FOR_MAINTENANCE, WITHDRAWN);
        allow(LOW_BATTERY, RELOCATE, LOW_BATTERY);

        allow(WITHDRAWN, REPORT_LOW_BATTERY, WITHDRAWN);
        allow(WITHDRAWN, RETURN_TO_SERVICE, AVAILABLE);
        allow(WITHDRAWN, RETIRE, RETIRED);

        allow(RETIRED, REPORT_LOW_BATTERY, RETIRED);
    }

    private static void allow(VehicleStatus from, VehicleAction action, VehicleStatus to) {
        ALLOWED.computeIfAbsent(from, k -> new EnumMap<>(VehicleAction.class)).put(action, to);
    }

    private static VehicleAvailability vehicleIn(VehicleStatus status) {
        return VehicleAvailability.rehydrate(VEHICLE, VehicleType.SCOOTER, TAFIRA, status, false);
    }

    private static void apply(VehicleAvailability vehicle, VehicleAction action) {
        switch (action) {
            case RESERVE -> vehicle.reserve();
            case RELEASE_RESERVATION -> vehicle.releaseReservation();
            case START_RIDE -> vehicle.startRide();
            case FINISH_RIDE -> vehicle.finishRide(MESA);
            case REPORT_LOW_BATTERY -> vehicle.reportLowBattery();
            case WITHDRAW_FOR_MAINTENANCE -> vehicle.withdrawForMaintenance();
            case RETURN_TO_SERVICE -> vehicle.returnToService(MESA);
            case RELOCATE -> vehicle.relocateTo(MESA);
            case RETIRE -> vehicle.retire();
        }
    }

    static Stream<Arguments> everyStateAndAction() {
        List<Arguments> cases = new ArrayList<>();
        for (VehicleStatus from : VehicleStatus.values()) {
            for (VehicleAction action : VehicleAction.values()) {
                VehicleStatus to = ALLOWED.getOrDefault(from, Map.of()).get(action);
                cases.add(Arguments.of(from, action, to));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("everyStateAndAction")
    @DisplayName("Cada par (estado, acción) hace la transición esperada o es rechazado")
    void transitionMatrix(VehicleStatus from, VehicleAction action, VehicleStatus expected) {
        VehicleAvailability vehicle = vehicleIn(from);

        if (expected == null) {
            InvalidStateTransitionError error = assertThrows(InvalidStateTransitionError.class, () -> apply(vehicle, action));
            assertEquals(from, error.from());
            assertEquals(action, error.action());
            assertEquals(from, vehicle.status(), "un intento ilegal no debe cambiar el estado");
            assertEquals(TAFIRA, vehicle.zoneId(), "un intento ilegal no debe cambiar la zona");
        } else {
            apply(vehicle, action);
            assertEquals(expected, vehicle.status());
        }
    }

    @Test
    void aPurchasedVehicleStartsAvailableInItsInitialZone() {
        VehicleAvailability vehicle = VehicleAvailability.registerPurchased(VEHICLE, VehicleType.BIKE, TAFIRA);

        assertEquals(AVAILABLE, vehicle.status());
        assertEquals(TAFIRA, vehicle.zoneId());
        assertTrue(vehicle.isReservable());
        assertFalse(vehicle.batteryLow());
    }

    @Test
    void onlyAnAvailableVehicleIsReservable() {
        for (VehicleStatus status : VehicleStatus.values()) {
            assertEquals(status == AVAILABLE, vehicleIn(status).isReservable(), status.name());
        }
    }

    @Test
    void theSameVehicleCannotBeReservedTwice() {
        VehicleAvailability vehicle = vehicleIn(AVAILABLE);
        vehicle.reserve();

        assertThrows(InvalidStateTransitionError.class, vehicle::reserve);
    }

    @Nested
    class LowBatteryWhileBusy {

        @Test
        void lowBatteryDuringARideLeavesTheVehicleLowBatteryWhenTheRideEnds() {
            VehicleAvailability vehicle = vehicleIn(IN_USE);

            vehicle.reportLowBattery();
            assertEquals(IN_USE, vehicle.status(), "no se interrumpe el viaje");

            vehicle.finishRide(MESA);
            assertEquals(LOW_BATTERY, vehicle.status());
            assertFalse(vehicle.isReservable());
        }

        @Test
        void lowBatteryDuringAReservationLeavesTheVehicleLowBatteryWhenReleased() {
            VehicleAvailability vehicle = vehicleIn(RESERVED);

            vehicle.reportLowBattery();
            assertEquals(RESERVED, vehicle.status(), "la reserva se respeta");

            vehicle.releaseReservation();
            assertEquals(LOW_BATTERY, vehicle.status());
        }

        @Test
        void returningToServiceClearsTheLowBatteryFlag() {
            VehicleAvailability vehicle = vehicleIn(LOW_BATTERY);
            vehicle.reportLowBattery();
            vehicle.withdrawForMaintenance();

            vehicle.returnToService(TAFIRA);
            vehicle.reserve();
            vehicle.releaseReservation();

            assertEquals(AVAILABLE, vehicle.status());
            assertFalse(vehicle.batteryLow());
        }
    }

    @Nested
    class Zones {

        @Test
        void finishingARideLeavesTheVehicleInTheDestinationZone() {
            VehicleAvailability vehicle = vehicleIn(IN_USE);
            vehicle.finishRide(MESA);
            assertEquals(MESA, vehicle.zoneId());
        }

        @Test
        void returningToServiceCanPutTheVehicleInAnotherZone() {
            VehicleAvailability vehicle = vehicleIn(WITHDRAWN);
            vehicle.returnToService(MESA);
            assertEquals(MESA, vehicle.zoneId());
        }

        @Test
        void aCompletedRelocationMovesTheVehicleWithoutChangingItsState() {
            VehicleAvailability vehicle = vehicleIn(AVAILABLE);
            vehicle.relocateTo(MESA);
            assertEquals(MESA, vehicle.zoneId());
            assertEquals(AVAILABLE, vehicle.status());
        }
    }

    @Test
    void theFullMaintenanceCycleSeenFromAvailability() {
        VehicleAvailability vehicle = VehicleAvailability.registerPurchased(VEHICLE, VehicleType.SCOOTER, TAFIRA);

        vehicle.reportLowBattery();          // VehicleBatteryLow
        vehicle.withdrawForMaintenance();    // VehicleWithdrawnForMaintenance
        assertEquals(WITHDRAWN, vehicle.status());

        vehicle.returnToService(MESA);       // VehicleReturnedToService
        assertEquals(AVAILABLE, vehicle.status());
        assertEquals(MESA, vehicle.zoneId());
    }

    @Test
    void identifiersFollowTheContractFormat() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleId("0042"));
        assertThrows(IllegalArgumentException.class, () -> new ZoneId("Mesa y López"));
        assertThrows(NullPointerException.class, () -> new VehicleId(null));
    }
}
