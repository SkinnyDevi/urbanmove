package com.urbanmove.maintenance.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.CommandResult;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.GetMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.IdempotencyKeyReusedException;
import com.urbanmove.maintenance.application.ListMaintenanceOrdersUseCase;
import com.urbanmove.maintenance.application.OpenMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.OpenOrderCommand;
import com.urbanmove.maintenance.application.OrderActionCommand;
import com.urbanmove.maintenance.application.OrderNotFoundException;
import com.urbanmove.maintenance.application.PerformOrderActionUseCase;
import com.urbanmove.maintenance.application.port.EventDispatcher;
import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.OrderSearch;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.InvalidStateTransitionError;
import com.urbanmove.maintenance.domain.MaintenanceAction;
import com.urbanmove.maintenance.domain.MaintenanceOrderStatus;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.VehicleType;
import com.urbanmove.maintenance.domain.event.VehicleReturnedToService;
import com.urbanmove.maintenance.domain.event.VehicleWithdrawnForMaintenance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MaintenanceUseCasesTest {

    private static final String CORRELATION = "c9a8b7d6-0000-4000-8000-000000000001";
    private static final VehicleId VEHICLE = new VehicleId("veh-0042");
    private static final ZoneId MESA = new ZoneId("mesa-y-lopez");
    private static final ZoneId TAFIRA = new ZoneId("tafira");
    private static final TechnicianId TECH = new TechnicianId("usr-tech-02");

    private InMemoryMaintenanceStore store;
    private final List<OutgoingEvent> dispatched = new ArrayList<>();
    private OpenMaintenanceOrderUseCase open;
    private PerformOrderActionUseCase act;

    @BeforeEach
    void setUp() {
        store = new InMemoryMaintenanceStore();
        AtomicInteger next = new AtomicInteger(1);
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
        EventDispatcher dispatcher = dispatched::addAll;
        open = new OpenMaintenanceOrderUseCase(store, store, store,
                () -> new MaintenanceOrderId("mnt-" + next.getAndIncrement()), clock);
        act = new PerformOrderActionUseCase(store, store, store, dispatcher, UUID::randomUUID, clock);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private CommandResult openFor(VehicleId vehicle, String key) {
        return open.execute(new OpenOrderCommand(key, CORRELATION, vehicle, VehicleType.SCOOTER,
                MaintenanceReason.LOW_BATTERY, MESA, null, null));
    }

    private CommandResult act(MaintenanceOrderId id, MaintenanceAction action) {
        return act.execute(OrderActionCommand.simple(key(), CORRELATION, id, action));
    }

    private CommandResult withdraw(MaintenanceOrderId id, String key) {
        return act.execute(new OrderActionCommand(key, CORRELATION, id, MaintenanceAction.WITHDRAW_FOR_MAINTENANCE,
                TECH, null, null));
    }

    @Nested
    class Opening {

        @Test
        void opensAPendingOrder() {
            CommandResult result = openFor(VEHICLE, key());

            assertFalse(result.replayed());
            assertEquals(MaintenanceOrderStatus.PENDING, result.order().status());
            assertTrue(new GetMaintenanceOrderUseCase(store).execute(result.order().orderId()).isActive());
        }

        @Test
        void theSameIdempotencyKeyReturnsTheSameOrderWithoutCreatingAnother() {
            String key = key();
            CommandResult first = openFor(VEHICLE, key);

            CommandResult second = openFor(VEHICLE, key);

            assertTrue(second.replayed());
            assertEquals(first.order().orderId(), second.order().orderId());
            assertEquals(1, new ListMaintenanceOrdersUseCase(store).execute(search()).items().size());
        }

        @Test
        void aVehicleCannotHaveTwoActiveOrders() {
            CommandResult first = openFor(VEHICLE, key());

            ActiveOrderExistsException error = assertThrows(ActiveOrderExistsException.class,
                    () -> openFor(VEHICLE, key()));
            assertEquals(first.order().orderId(), error.existingOrderId());
        }

        @Test
        void aNewOrderCanBeOpenedOnceThePreviousOneIsClosed() {
            MaintenanceOrderId first = openFor(VEHICLE, key()).order().orderId();
            act.execute(new OrderActionCommand(key(), CORRELATION, first, MaintenanceAction.CANCEL, null, null, "Duplicada"));

            assertDoesNotThrow(() -> openFor(VEHICLE, key()));
        }

        @Test
        void openingPublishesNoEvent() {
            openFor(VEHICLE, key());

            assertTrue(store.outbox.isEmpty());
            assertTrue(dispatched.isEmpty());
        }
    }

    @Nested
    class Actions {

        private MaintenanceOrderId id;

        @BeforeEach
        void openOrder() {
            id = openFor(VEHICLE, key()).order().orderId();
        }

        @Test
        void withdrawingStoresAndDispatchesVehicleWithdrawnForMaintenance() {
            withdraw(id, key());

            assertEquals(1, store.outbox.size(), "el evento se guarda junto a la orden");
            assertEquals(store.outbox, dispatched, "y después se publica");
            OutgoingEvent event = dispatched.get(0);
            assertEquals(CORRELATION, event.correlationId());
            VehicleWithdrawnForMaintenance payload = assertInstanceOf(VehicleWithdrawnForMaintenance.class, event.event());
            assertEquals(TECH, payload.technicianId());
            assertEquals(id, payload.maintenanceOrderId());
        }

        @Test
        void theFullCycleEndsWithVehicleReturnedToService() {
            withdraw(id, key());
            act(id, MaintenanceAction.START_CHARGING);
            act(id, MaintenanceAction.MARK_READY_FOR_SERVICE);
            CommandResult returned = act.execute(new OrderActionCommand(key(), CORRELATION, id,
                    MaintenanceAction.RETURN_TO_SERVICE, null, TAFIRA, null));

            assertEquals(MaintenanceOrderStatus.RETURNED, returned.order().status());
            assertEquals(2, dispatched.size());
            assertInstanceOf(VehicleReturnedToService.class, dispatched.get(1).event());
            assertEquals(TAFIRA, ((VehicleReturnedToService) dispatched.get(1).event()).zoneId());
        }

        @Test
        void repeatingAnActionWithTheSameKeyDoesNotRepeatItsEffect() {
            String key = key();
            withdraw(id, key);

            CommandResult again = withdraw(id, key);

            assertTrue(again.replayed());
            assertEquals(MaintenanceOrderStatus.WITHDRAWN, again.order().status());
            assertEquals(1, dispatched.size(), "el evento no se publica dos veces");
        }

        @Test
        void reusingAKeyForAnotherActionIsRejected() {
            String key = key();
            withdraw(id, key);

            assertThrows(IdempotencyKeyReusedException.class, () -> act.execute(
                    OrderActionCommand.simple(key, CORRELATION, id, MaintenanceAction.START_CHARGING)));
        }

        @Test
        void anIllegalTransitionChangesNothing() {
            assertThrows(InvalidStateTransitionError.class, () -> act(id, MaintenanceAction.START_CHARGING));

            assertEquals(MaintenanceOrderStatus.PENDING, new GetMaintenanceOrderUseCase(store).execute(id).status());
            assertTrue(dispatched.isEmpty());
        }

        @Test
        void aConcurrentModificationIsDetected() {
            MaintenanceOrderReader racing = new MaintenanceOrderReader() {
                @Override
                public java.util.Optional<com.urbanmove.maintenance.domain.MaintenanceOrder> findById(MaintenanceOrderId orderId) {
                    var order = store.findById(orderId);
                    store.bumpVersion(orderId); // otra petición escribe justo después de nuestra lectura
                    return order;
                }

                @Override
                public com.urbanmove.maintenance.application.port.OrderPage search(OrderSearch search) {
                    return store.search(search);
                }
            };
            PerformOrderActionUseCase racingAct = new PerformOrderActionUseCase(racing, store, store,
                    dispatched::addAll, UUID::randomUUID, Clock.systemUTC());

            assertThrows(ConcurrentUpdateException.class, () -> racingAct.execute(new OrderActionCommand(key(),
                    CORRELATION, id, MaintenanceAction.WITHDRAW_FOR_MAINTENANCE, TECH, null, null)));
            assertTrue(dispatched.isEmpty());
        }

        @Test
        void anUnknownOrderIsNotFound() {
            assertThrows(OrderNotFoundException.class,
                    () -> act(new MaintenanceOrderId("mnt-unknown"), MaintenanceAction.START_REPAIR));
        }
    }

    @Test
    void listingFiltersByStatusAndVehicle() {
        MaintenanceOrderId a = openFor(VEHICLE, key()).order().orderId();
        openFor(new VehicleId("veh-0043"), key());
        withdraw(a, key());

        var withdrawn = new ListMaintenanceOrdersUseCase(store).execute(new OrderSearch(
                Set.of(MaintenanceOrderStatus.WITHDRAWN), null, null, null, 50, null));
        var forVehicle = new ListMaintenanceOrdersUseCase(store).execute(new OrderSearch(
                Set.of(), null, new VehicleId("veh-0043"), null, 50, null));

        assertEquals(List.of(a), withdrawn.items().stream().map(o -> o.orderId()).toList());
        assertEquals(1, forVehicle.items().size());
    }

    private static OrderSearch search() {
        return new OrderSearch(Set.of(), null, null, null, OrderSearch.DEFAULT_LIMIT, null);
    }
}
