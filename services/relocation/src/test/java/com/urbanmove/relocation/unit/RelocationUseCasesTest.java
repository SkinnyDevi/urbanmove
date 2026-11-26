package com.urbanmove.relocation.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.relocation.application.CommandResult;
import com.urbanmove.relocation.application.ConcurrentUpdateException;
import com.urbanmove.relocation.application.CreateOrderFromHotspotUseCase;
import com.urbanmove.relocation.application.DemandHotspot;
import com.urbanmove.relocation.application.HotspotOutcome;
import com.urbanmove.relocation.application.IdempotencyKeyReusedException;
import com.urbanmove.relocation.application.ListRelocationOrdersUseCase;
import com.urbanmove.relocation.application.OrderNotFoundException;
import com.urbanmove.relocation.application.PerformRelocationActionUseCase;
import com.urbanmove.relocation.application.RelocationCommand;
import com.urbanmove.relocation.application.port.OrderSearch;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.NotAssigneeException;
import com.urbanmove.relocation.domain.RelocationAction;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.VehicleType;
import com.urbanmove.relocation.domain.event.RelocationOrderCompleted;
import com.urbanmove.relocation.domain.event.RelocationOrderCreated;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Casos de uso de relocation: creación desde hotspots, acciones, idempotencia y concurrencia. */
class RelocationUseCasesTest {
    private static final String CORRELATION = "c9a8b7d6-0000-4000-8000-000000000001";
    private static final TechnicianId TECH = new TechnicianId("usr-tech-02");
    private static final Instant NOW = Instant.parse("2026-11-16T08:00:00Z");

    private InMemoryRelocationStore store;
    private final List<OutgoingEvent> dispatched = new ArrayList<>();
    private CreateOrderFromHotspotUseCase fromHotspot;
    private PerformRelocationActionUseCase act;

    @BeforeEach
    void setUp() {
        store = new InMemoryRelocationStore();
        AtomicInteger next = new AtomicInteger(1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        fromHotspot = new CreateOrderFromHotspotUseCase(store, store, store, dispatched::addAll,
                () -> new RelocationOrderId("rel-" + next.getAndIncrement()), UUID::randomUUID, clock,
                Duration.ofHours(2));
        act = new PerformRelocationActionUseCase(store, store, store, dispatched::addAll, UUID::randomUUID, clock);
    }

    private static DemandHotspot hotspot(String target, String source, int quantity) {
        return new DemandHotspot(UUID.randomUUID(), CORRELATION, NOW, target, source, VehicleType.SCOOTER, quantity,
                RelocationStrategy.THRESHOLD_RULE);
    }

    private RelocationOrder onlyOrder() {
        return new ListRelocationOrdersUseCase(store).execute(new OrderSearch(Set.of(), null, null, null, 50, null))
                .items().get(0);
    }

    private CommandResult act(RelocationAction action, List<VehicleId> vehicles) {
        return act.execute(new RelocationCommand(UUID.randomUUID().toString(), CORRELATION, onlyOrder().orderId(),
                action, TECH, vehicles, null));
    }

    @Test
    void aHotspotCreatesAnOrderWithADeadlineAndPublishesIt() {
        assertEquals(HotspotOutcome.CREATED, fromHotspot.execute(hotspot("tafira", "las-canteras", 5)));

        RelocationOrder order = onlyOrder();
        assertEquals(RelocationOrderStatus.CREATED, order.status());
        assertEquals(NOW.plus(Duration.ofHours(2)), order.snapshot().dueAt());
        assertInstanceOf(RelocationOrderCreated.class, dispatched.get(0).event());
        assertEquals(CORRELATION, dispatched.get(0).correlationId());
    }

    @Test
    void theSameHotspotTwiceCreatesOneOrder() {
        DemandHotspot hotspot = hotspot("tafira", "las-canteras", 5);
        fromHotspot.execute(hotspot);

        assertEquals(HotspotOutcome.DUPLICATE, fromHotspot.execute(hotspot));
        assertEquals(1, store.outbox.size());
    }

    @Test
    void anotherHotspotForAZoneAlreadyBeingServedIsIgnored() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 5));

        assertEquals(HotspotOutcome.ACTIVE_ORDER_EXISTS, fromHotspot.execute(hotspot("tafira", "triana", 2)));
    }

    @Test
    void anInvalidHotspotIsRejected() {
        assertEquals(HotspotOutcome.REJECTED, fromHotspot.execute(hotspot("tafira", "tafira", 5)));
        assertEquals(HotspotOutcome.REJECTED, fromHotspot.execute(hotspot("tafira", "las-canteras", 0)));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void theFullCyclePublishesRelocationOrderCompleted() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 3));
        List<VehicleId> moved = List.of(new VehicleId("veh-0063"), new VehicleId("veh-0071"));

        act(RelocationAction.ASSIGN, List.of());
        act(RelocationAction.START, List.of());
        CommandResult done = act(RelocationAction.COMPLETE, moved);

        assertEquals(RelocationOrderStatus.COMPLETED, done.order().status());
        RelocationOrderCompleted event = assertInstanceOf(RelocationOrderCompleted.class, dispatched.get(1).event());
        assertEquals(moved, event.vehicleIds());
        assertEquals(0, event.delaySeconds());
    }

    @Test
    void aZoneCanGetANewOrderOnceThePreviousOneIsClosed() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 3));
        act.execute(new RelocationCommand(UUID.randomUUID().toString(), CORRELATION, onlyOrder().orderId(),
                RelocationAction.CANCEL, null, List.of(), "Resuelto"));

        assertEquals(HotspotOutcome.CREATED, fromHotspot.execute(hotspot("tafira", "triana", 2)));
    }

    @Test
    void repeatingAnActionDoesNotRepeatItsEffect() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 3));
        RelocationCommand assign = RelocationCommand.byTechnician(UUID.randomUUID().toString(), CORRELATION,
                onlyOrder().orderId(), RelocationAction.ASSIGN, TECH);
        act.execute(assign);

        assertTrue(act.execute(assign).replayed());
        assertThrows(IdempotencyKeyReusedException.class, () -> act.execute(RelocationCommand.byTechnician(
                assign.idempotencyKey(), CORRELATION, assign.orderId(), RelocationAction.START, TECH)));
    }

    @Test
    void anotherTechnicianCannotStartSomeoneElsesOrder() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 3));
        act(RelocationAction.ASSIGN, List.of());

        assertThrows(NotAssigneeException.class, () -> act.execute(RelocationCommand.byTechnician(
                UUID.randomUUID().toString(), CORRELATION, onlyOrder().orderId(), RelocationAction.START,
                new TechnicianId("usr-tech-07"))));
    }

    @Test
    void aConcurrentModificationIsDetected() {
        fromHotspot.execute(hotspot("tafira", "las-canteras", 3));
        RelocationOrderId id = onlyOrder().orderId();
        PerformRelocationActionUseCase racing = new PerformRelocationActionUseCase(orderId -> {
            var read = store.findById(orderId);
            store.bumpVersion(orderId);
            return read;
        }, store, store, dispatched::addAll, UUID::randomUUID, Clock.systemUTC());

        assertThrows(ConcurrentUpdateException.class, () -> racing.execute(RelocationCommand.byTechnician(
                UUID.randomUUID().toString(), CORRELATION, id, RelocationAction.ASSIGN, TECH)));
    }

    @Test
    void anUnknownOrderIsNotFound() {
        assertThrows(OrderNotFoundException.class, () -> act.execute(RelocationCommand.byTechnician(
                UUID.randomUUID().toString(), CORRELATION, new RelocationOrderId("rel-none"),
                RelocationAction.ASSIGN, TECH)));
    }
}
