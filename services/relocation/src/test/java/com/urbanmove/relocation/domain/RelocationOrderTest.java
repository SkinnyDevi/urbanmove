package com.urbanmove.relocation.domain;

import static com.urbanmove.relocation.domain.RelocationAction.*;
import static com.urbanmove.relocation.domain.RelocationOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.event.RelocationOrderCompleted;
import com.urbanmove.relocation.domain.event.RelocationOrderCreated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Máquina de estados de la orden de recolocación y sus reglas. */
class RelocationOrderTest {
    private static final RelocationOrderId ORDER = new RelocationOrderId("rel-0009");
    private static final TechnicianId TECH = new TechnicianId("usr-tech-02");
    private static final TechnicianId OTHER = new TechnicianId("usr-tech-07");
    private static final Route ROUTE = new Route(new ZoneId("las-canteras"), new ZoneId("tafira"), VehicleType.SCOOTER, 3);
    private static final Instant CREATED_AT = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant DUE = CREATED_AT.plusSeconds(7200);
    private static final List<VehicleId> TWO = List.of(new VehicleId("veh-0063"), new VehicleId("veh-0071"));

    private static final Map<RelocationOrderStatus, Map<RelocationAction, RelocationOrderStatus>> ALLOWED =
            new EnumMap<>(RelocationOrderStatus.class);

    static {
        allow(CREATED, ASSIGN, ASSIGNED);
        allow(CREATED, CANCEL, CANCELLED);
        allow(ASSIGNED, START, IN_PROGRESS);
        allow(ASSIGNED, CANCEL, CANCELLED);
        allow(IN_PROGRESS, COMPLETE, COMPLETED);
    }

    private static void allow(RelocationOrderStatus from, RelocationAction action, RelocationOrderStatus to) {
        ALLOWED.computeIfAbsent(from, k -> new EnumMap<>(RelocationAction.class)).put(action, to);
    }

    private static RelocationOrder orderIn(RelocationOrderStatus status) {
        return RelocationOrder.rehydrate(new RelocationOrder.Snapshot(ORDER, ROUTE, RelocationStrategy.THRESHOLD_RULE,
                null, status, status == CREATED ? null : TECH, List.of(), CREATED_AT, DUE, null, null, null, 4));
    }

    private static void apply(RelocationOrder order, RelocationAction action) {
        switch (action) {
            case ASSIGN -> order.assign(TECH);
            case START -> order.start(TECH, CREATED_AT.plusSeconds(60));
            case COMPLETE -> order.complete(TECH, TWO, CREATED_AT.plusSeconds(3600));
            case CANCEL -> order.cancel("Ya no hace falta");
        }
    }

    static Stream<Arguments> everyStateAndAction() {
        List<Arguments> cases = new ArrayList<>();
        for (RelocationOrderStatus from : RelocationOrderStatus.values()) {
            for (RelocationAction action : RelocationAction.values()) {
                cases.add(Arguments.of(from, action, ALLOWED.getOrDefault(from, Map.of()).get(action)));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @MethodSource("everyStateAndAction")
    void transitionMatrix(RelocationOrderStatus from, RelocationAction action, RelocationOrderStatus expected) {
        RelocationOrder order = orderIn(from);

        if (expected == null) {
            InvalidStateTransitionError error = assertThrows(InvalidStateTransitionError.class, () -> apply(order, action));
            assertEquals(from, error.from());
            assertEquals(action, error.action());
            assertEquals(from, order.status());
        } else {
            apply(order, action);
            assertEquals(expected, order.status());
        }
    }

    @Test
    void creatingRecordsRelocationOrderCreated() {
        UUID hotspot = UUID.randomUUID();
        RelocationOrder order = RelocationOrder.create(ORDER, ROUTE, RelocationStrategy.PREDICTIVE_MODEL, hotspot,
                CREATED_AT, DUE);

        RelocationOrderCreated event = assertInstanceOf(RelocationOrderCreated.class, order.pullDomainEvents().get(0));
        assertEquals(CREATED, order.status());
        assertEquals(hotspot, event.triggeredByEventId());
        assertEquals(3, event.quantity());
        assertTrue(order.pullDomainEvents().isEmpty());
    }

    @Test
    void completingOnTimeHasNoDelay() {
        RelocationOrder order = orderIn(IN_PROGRESS);

        order.complete(TECH, TWO, DUE.minusSeconds(10));

        RelocationOrderCompleted event = assertInstanceOf(RelocationOrderCompleted.class, order.pullDomainEvents().get(0));
        assertEquals(0, event.delaySeconds());
        assertEquals(TWO, event.vehicleIds());
        assertFalse(order.isActive());
    }

    @Test
    void completingLateMeasuresTheDelayAgainstTheDeadline() {
        RelocationOrder order = orderIn(IN_PROGRESS);

        order.complete(TECH, TWO, DUE.plusSeconds(600));

        assertEquals(600, ((RelocationOrderCompleted) order.pullDomainEvents().get(0)).delaySeconds());
    }

    @Test
    void onlyTheAssignedTechnicianCanStartOrComplete() {
        assertThrows(NotAssigneeException.class, () -> orderIn(ASSIGNED).start(OTHER, CREATED_AT));
        assertThrows(NotAssigneeException.class, () -> orderIn(IN_PROGRESS).complete(OTHER, TWO, CREATED_AT));
    }

    @Test
    void movedVehiclesMustBeValid() {
        RelocationOrder order = orderIn(IN_PROGRESS);
        VehicleId one = TWO.get(0);
        List<VehicleId> four = List.of(one, TWO.get(1), new VehicleId("veh-0072"), new VehicleId("veh-0080"));

        assertThrows(InvalidRelocationException.class, () -> order.complete(TECH, List.of(), CREATED_AT));
        assertThrows(InvalidRelocationException.class, () -> order.complete(TECH, List.of(one, one), CREATED_AT));
        assertThrows(InvalidRelocationException.class, () -> order.complete(TECH, four, CREATED_AT));
        assertEquals(IN_PROGRESS, order.status());
    }

    @Test
    void aRouteNeedsTwoDifferentZonesAndAPositiveQuantity() {
        ZoneId tafira = new ZoneId("tafira");
        assertThrows(InvalidRelocationException.class, () -> new Route(tafira, tafira, VehicleType.BIKE, 1));
        assertThrows(InvalidRelocationException.class,
                () -> new Route(new ZoneId("triana"), tafira, VehicleType.BIKE, 0));
    }

    @Test
    void cancellingRequiresAReason() {
        RelocationOrder order = orderIn(CREATED);

        assertThrows(InvalidRelocationException.class, () -> order.cancel(" "));
        order.cancel("Déficit resuelto");

        assertEquals("Déficit resuelto", order.snapshot().cancellationReason());
    }

    @Test
    void snapshotAndRehydrateKeepEverything() {
        RelocationOrder order = orderIn(IN_PROGRESS);
        order.complete(TECH, TWO, DUE);

        assertEquals(order.snapshot(), RelocationOrder.rehydrate(order.snapshot()).snapshot());
    }
}
