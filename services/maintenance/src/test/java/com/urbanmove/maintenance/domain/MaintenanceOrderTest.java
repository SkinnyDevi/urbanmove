package com.urbanmove.maintenance.domain;

import static com.urbanmove.maintenance.domain.MaintenanceAction.*;
import static com.urbanmove.maintenance.domain.MaintenanceOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.event.DomainEvent;
import com.urbanmove.maintenance.domain.event.VehicleReturnedToService;
import com.urbanmove.maintenance.domain.event.VehicleWithdrawnForMaintenance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Máquina de estados de la orden de mantenimiento y sus eventos. */
class MaintenanceOrderTest {
    private static final MaintenanceOrderId ORDER = new MaintenanceOrderId("mnt-0031");
    private static final VehicleId VEHICLE = new VehicleId("veh-0042");
    private static final ZoneId MESA = new ZoneId("mesa-y-lopez");
    private static final ZoneId TAFIRA = new ZoneId("tafira");
    private static final TechnicianId TECH = new TechnicianId("usr-tech-02");
    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    private static final Map<MaintenanceOrderStatus, Map<MaintenanceAction, MaintenanceOrderStatus>> ALLOWED =
            new EnumMap<>(MaintenanceOrderStatus.class);

    static {
        allow(PENDING, WITHDRAW_FOR_MAINTENANCE, WITHDRAWN);
        allow(PENDING, CANCEL, CANCELLED);

        allow(WITHDRAWN, START_CHARGING, CHARGING);
        allow(WITHDRAWN, START_REPAIR, REPAIRING);
        allow(WITHDRAWN, MARK_READY_FOR_SERVICE, READY_FOR_SERVICE);

        allow(CHARGING, START_REPAIR, REPAIRING);
        allow(CHARGING, MARK_READY_FOR_SERVICE, READY_FOR_SERVICE);

        allow(REPAIRING, START_CHARGING, CHARGING);
        allow(REPAIRING, MARK_READY_FOR_SERVICE, READY_FOR_SERVICE);

        allow(READY_FOR_SERVICE, RETURN_TO_SERVICE, RETURNED);
    }

    private static void allow(MaintenanceOrderStatus from, MaintenanceAction action, MaintenanceOrderStatus to) {
        ALLOWED.computeIfAbsent(from, k -> new EnumMap<>(MaintenanceAction.class)).put(action, to);
    }

    private static MaintenanceOrder newOrder() {
        return MaintenanceOrder.open(ORDER, VEHICLE, VehicleType.SCOOTER, MaintenanceReason.LOW_BATTERY, MESA,
                T0.minusSeconds(600), null, null);
    }

    private static MaintenanceOrder orderIn(MaintenanceOrderStatus status) {
        boolean withdrawn = status != PENDING && status != CANCELLED;
        return MaintenanceOrder.rehydrate(new MaintenanceOrder.Snapshot(ORDER, VEHICLE, VehicleType.SCOOTER,
                MaintenanceReason.LOW_BATTERY, MESA, status, withdrawn ? TECH : null, Set.of(), null, null,
                T0.minusSeconds(600), withdrawn ? T0 : null, null, null, null, 3));
    }

    private static void apply(MaintenanceOrder order, MaintenanceAction action) {
        switch (action) {
            case WITHDRAW_FOR_MAINTENANCE -> order.withdrawForMaintenance(TECH, T0);
            case START_CHARGING -> order.startCharging();
            case START_REPAIR -> order.startRepair();
            case MARK_READY_FOR_SERVICE -> order.markReadyForService();
            case RETURN_TO_SERVICE -> order.returnToService(MESA, T0.plusSeconds(60));
            case CANCEL -> order.cancel("Orden duplicada");
        }
    }

    static Stream<Arguments> everyStateAndAction() {
        List<Arguments> cases = new ArrayList<>();
        for (MaintenanceOrderStatus from : MaintenanceOrderStatus.values()) {
            for (MaintenanceAction action : MaintenanceAction.values()) {
                cases.add(Arguments.of(from, action, ALLOWED.getOrDefault(from, Map.of()).get(action)));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("everyStateAndAction")
    @DisplayName("Cada par (estado, acción) hace la transición esperada o es rechazado")
    void transitionMatrix(MaintenanceOrderStatus from, MaintenanceAction action, MaintenanceOrderStatus expected) {
        MaintenanceOrder order = orderIn(from);

        if (expected == null) {
            InvalidStateTransitionError error = assertThrows(InvalidStateTransitionError.class, () -> apply(order, action));
            assertEquals(from, error.from());
            assertEquals(action, error.action());
            assertEquals(from, order.status(), "un intento ilegal no debe cambiar el estado");
            assertTrue(order.pullDomainEvents().isEmpty(), "un intento ilegal no debe publicar eventos");
        } else {
            apply(order, action);
            assertEquals(expected, order.status());
        }
    }

    @Test
    void aNewOrderIsPendingAndHasNoEvents() {
        MaintenanceOrder order = newOrder();

        assertEquals(PENDING, order.status());
        assertNull(order.snapshot().technicianId());
        assertTrue(order.isActive());
        assertTrue(order.pullDomainEvents().isEmpty());
    }

    @Test
    void withdrawingRecordsVehicleWithdrawnForMaintenance() {
        MaintenanceOrder order = newOrder();

        order.withdrawForMaintenance(TECH, T0);

        List<DomainEvent> events = order.pullDomainEvents();
        assertEquals(1, events.size());
        VehicleWithdrawnForMaintenance event = assertInstanceOf(VehicleWithdrawnForMaintenance.class, events.get(0));
        assertEquals("VehicleWithdrawnForMaintenance", event.eventType());
        assertEquals(VEHICLE, event.vehicleId());
        assertEquals(ORDER, event.maintenanceOrderId());
        assertEquals(MaintenanceReason.LOW_BATTERY, event.reason());
        assertEquals(MESA, event.zoneId());
        assertEquals(TECH, event.technicianId());
        assertEquals(T0, event.occurredAt());
    }

    @Test
    void pullingEventsEmptiesTheList() {
        MaintenanceOrder order = newOrder();
        order.withdrawForMaintenance(TECH, T0);

        order.pullDomainEvents();

        assertTrue(order.pullDomainEvents().isEmpty());
    }

    @Test
    void fullCycleRecordsWorkPerformedAndTimeOutOfService() {
        MaintenanceOrder order = newOrder();

        order.withdrawForMaintenance(TECH, T0);
        order.startCharging();
        order.startRepair();
        order.markReadyForService();
        order.returnToService(TAFIRA, T0.plusSeconds(9000));

        List<DomainEvent> events = order.pullDomainEvents();
        assertEquals(2, events.size());
        VehicleReturnedToService returned = assertInstanceOf(VehicleReturnedToService.class, events.get(1));
        assertEquals("VehicleReturnedToService", returned.eventType());
        assertEquals(List.of(MaintenanceWork.RECHARGE, MaintenanceWork.REPAIR), returned.workPerformed());
        assertEquals(TAFIRA, returned.zoneId(), "puede volver a una zona distinta");
        assertEquals(9000, returned.outOfServiceSeconds());
        assertEquals(RETURNED, order.status());
    }

    @Test
    void readyWithoutChargingOrRepairingCountsAsAnInspection() {
        MaintenanceOrder order = newOrder();
        order.withdrawForMaintenance(TECH, T0);

        order.markReadyForService();

        assertEquals(Set.of(MaintenanceWork.INSPECTION), order.workPerformed());
    }

    @Test
    void anOrderCannotBeCancelledOnceTheVehicleIsWithdrawn() {
        MaintenanceOrder order = newOrder();
        order.withdrawForMaintenance(TECH, T0);

        assertThrows(InvalidStateTransitionError.class, () -> order.cancel("Ya no hace falta"));
    }

    @Test
    void aVehicleCannotGoBackToTheStreetWithoutBeingMarkedReady() {
        MaintenanceOrder order = newOrder();
        order.withdrawForMaintenance(TECH, T0);
        order.startRepair();

        assertThrows(InvalidStateTransitionError.class, () -> order.returnToService(MESA, T0.plusSeconds(60)));
    }

    @Test
    void returningRecordsWhenAndWhereTheVehicleWentBack() {
        MaintenanceOrder order = newOrder();
        order.withdrawForMaintenance(TECH, T0);
        order.startCharging();
        order.markReadyForService();

        order.returnToService(TAFIRA, T0.plusSeconds(60));

        MaintenanceOrder.Snapshot snapshot = order.snapshot();
        assertEquals(T0.plusSeconds(60), snapshot.returnedAt());
        assertEquals(TAFIRA, snapshot.returnZoneId());
        assertFalse(order.isActive());
    }

    @Test
    void cancellingKeepsTheReasonAndRequiresOne() {
        MaintenanceOrder order = newOrder();

        assertThrows(IllegalArgumentException.class, () -> order.cancel(" "));
        assertEquals(PENDING, order.status(), "un motivo vacío no cancela");

        order.cancel("Falsa alarma de batería");
        assertEquals("Falsa alarma de batería", order.snapshot().cancellationReason());
        assertFalse(order.isActive());
    }

    @Test
    void snapshotAndRehydrateKeepEverything() {
        MaintenanceOrder order = MaintenanceOrder.open(ORDER, VEHICLE, VehicleType.BIKE, MaintenanceReason.BREAKDOWN,
                MESA, T0, "Freno trasero roto", java.util.UUID.fromString("8c1e0c2a-1111-4222-8333-444455556666"));
        order.withdrawForMaintenance(TECH, T0.plusSeconds(10));
        order.startRepair();

        MaintenanceOrder copy = MaintenanceOrder.rehydrate(order.snapshot());

        assertEquals(order.snapshot(), copy.snapshot());
    }

    @Test
    void textFieldsHaveAMaximumLength() {
        assertThrows(IllegalArgumentException.class, () -> MaintenanceOrder.open(ORDER, VEHICLE, VehicleType.BIKE,
                MaintenanceReason.BREAKDOWN, MESA, T0, "x".repeat(MaintenanceOrder.MAX_NOTES + 1), null));
    }

    @Test
    void identifiersFollowTheContractFormat() {
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceOrderId("31"));
        assertThrows(IllegalArgumentException.class, () -> new TechnicianId("tech-02"));
    }
}
