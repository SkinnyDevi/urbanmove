package com.urbanmove.inventory.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.inventory.application.ApplyInventoryEventUseCase;
import com.urbanmove.inventory.application.CommandResult;
import com.urbanmove.inventory.application.ConcurrentUpdateException;
import com.urbanmove.inventory.application.EventMetadata;
import com.urbanmove.inventory.application.EventOutcome;
import com.urbanmove.inventory.application.IdempotencyKeyReusedException;
import com.urbanmove.inventory.application.IncomingEvent;
import com.urbanmove.inventory.application.InventoryEvent;
import com.urbanmove.inventory.application.RetireCommand;
import com.urbanmove.inventory.application.RetireVehicleUseCase;
import com.urbanmove.inventory.application.SeedFleetUseCase;
import com.urbanmove.inventory.application.UnknownVehicleException;
import com.urbanmove.inventory.application.VehicleNotFoundException;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.domain.InvalidStateTransitionError;
import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.ServiceStatus;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import com.urbanmove.inventory.domain.event.VehicleRetired;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Casos de uso de inventory: eventos del bus, baja definitiva, idempotencia y concurrencia. */
class InventoryUseCasesTest {
    private static final VehicleId VEHICLE = new VehicleId("veh-0042");
    private static final String CORRELATION = "c9a8b7d6-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private InMemoryInventoryStore store;
    private ApplyInventoryEventUseCase apply;
    private RetireVehicleUseCase retire;
    private final List<OutgoingEvent> dispatched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new InMemoryInventoryStore();
        apply = new ApplyInventoryEventUseCase(store, store, store);
        retire = new RetireVehicleUseCase(store, store, store, dispatched::addAll, UUID::randomUUID,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IncomingEvent event(InventoryEvent event) {
        return new IncomingEvent(new EventMetadata(UUID.randomUUID(), event.getClass().getSimpleName(), CORRELATION,
                NOW), event);
    }

    private static InventoryEvent.Purchased purchased(VehicleId id) {
        return new InventoryEvent.Purchased(id, VehicleType.SCOOTER, "Segway Ninebot Max G30",
                LocalDate.parse("2026-11-02"), new Money(new BigDecimal("649.00"), Money.EUR));
    }

    private ServiceStatus status() {
        return store.findById(VEHICLE).orElseThrow().status();
    }

    private static RetireCommand retireCommand(String key) {
        return new RetireCommand(key, CORRELATION, VEHICLE, "Fin de vida útil");
    }

    @Test
    void vehiclePurchasedRegistersTheAssetInService() {
        assertEquals(EventOutcome.APPLIED, apply.execute(event(purchased(VEHICLE))));

        assertEquals(ServiceStatus.IN_SERVICE, status());
    }

    @Test
    void maintenanceEventsTellInventoryWhetherTheVehicleIsOnTheStreet() {
        apply.execute(event(purchased(VEHICLE)));

        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));
        assertEquals(ServiceStatus.WITHDRAWN, status());
        apply.execute(event(new InventoryEvent.ReturnedToService(VEHICLE)));
        assertEquals(ServiceStatus.IN_SERVICE, status());
    }

    @Test
    void aRepeatedEventIsIgnored() {
        apply.execute(event(purchased(VEHICLE)));
        IncomingEvent withdrawn = event(new InventoryEvent.Withdrawn(VEHICLE));
        apply.execute(withdrawn);
        apply.execute(event(new InventoryEvent.ReturnedToService(VEHICLE)));

        assertEquals(EventOutcome.DUPLICATE, apply.execute(withdrawn));
        assertEquals(ServiceStatus.IN_SERVICE, status());
    }

    @Test
    void anIllegalEventIsRejectedAndRecorded() {
        apply.execute(event(purchased(VEHICLE)));
        IncomingEvent back = event(new InventoryEvent.ReturnedToService(VEHICLE));

        assertEquals(EventOutcome.REJECTED, apply.execute(back));
        assertEquals(EventOutcome.REJECTED, store.processed.get(back.metadata().eventId()));
    }

    @Test
    void anEventForAnUnknownVehicleFailsSoSqsRetriesIt() {
        IncomingEvent withdrawn = event(new InventoryEvent.Withdrawn(VEHICLE));

        assertThrows(UnknownVehicleException.class, () -> apply.execute(withdrawn));
        assertFalse(store.isProcessed(withdrawn.metadata().eventId()));
    }

    @Test
    void aSecondPurchaseOfTheSameVehicleChangesNothing() {
        apply.execute(event(purchased(VEHICLE)));
        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));

        assertEquals(EventOutcome.APPLIED, apply.execute(event(purchased(VEHICLE))));
        assertEquals(ServiceStatus.WITHDRAWN, status());
    }

    @Test
    void aWithdrawnVehicleCanBeRetiredAndVehicleRetiredIsPublished() {
        apply.execute(event(purchased(VEHICLE)));
        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));

        CommandResult result = retire.execute(retireCommand(UUID.randomUUID().toString()));

        assertEquals(ServiceStatus.RETIRED, result.vehicle().status());
        assertEquals(NOW, result.vehicle().snapshot().retiredAt());
        VehicleRetired event = assertInstanceOf(VehicleRetired.class, dispatched.get(0).event());
        assertEquals(VEHICLE, event.vehicleId());
        assertEquals(CORRELATION, dispatched.get(0).correlationId());
        assertEquals(1, store.outbox.size(), "el evento se guarda en la bandeja de salida");
    }

    @Test
    void aVehicleOnTheStreetCannotBeRetired() {
        apply.execute(event(purchased(VEHICLE)));

        InvalidStateTransitionError error = assertThrows(InvalidStateTransitionError.class,
                () -> retire.execute(retireCommand(UUID.randomUUID().toString())));

        assertEquals(ServiceStatus.IN_SERVICE, error.from());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void repeatingTheRetirementDoesNotRepeatItsEffect() {
        apply.execute(event(purchased(VEHICLE)));
        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));
        String key = UUID.randomUUID().toString();
        retire.execute(retireCommand(key));

        assertTrue(retire.execute(retireCommand(key)).replayed());
        assertEquals(1, dispatched.size());
        assertThrows(IdempotencyKeyReusedException.class, () -> retire.execute(
                new RetireCommand(key, CORRELATION, new VehicleId("veh-0043"), "Otro")));
    }

    @Test
    void aConcurrentChangeIsDetected() {
        apply.execute(event(purchased(VEHICLE)));
        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));
        RetireVehicleUseCase racing = new RetireVehicleUseCase(id -> {
            var read = store.findById(id);
            store.bumpVersion(id);
            return read;
        }, store, store, dispatched::addAll, UUID::randomUUID, Clock.systemUTC());

        assertThrows(ConcurrentUpdateException.class, () -> racing.execute(retireCommand(UUID.randomUUID().toString())));
    }

    @Test
    void retiringAnUnknownVehicleIsNotFound() {
        assertThrows(VehicleNotFoundException.class, () -> retire.execute(retireCommand(UUID.randomUUID().toString())));
    }

    @Test
    void theSeedDoesNotOverwriteExistingAssets() {
        apply.execute(event(purchased(VEHICLE)));
        apply.execute(event(new InventoryEvent.Withdrawn(VEHICLE)));
        SeedFleetUseCase seed = new SeedFleetUseCase(() -> List.of(purchased(VEHICLE), purchased(new VehicleId("veh-0043"))),
                store, Clock.systemUTC());

        assertEquals(1, seed.execute());
        assertEquals(ServiceStatus.WITHDRAWN, status());
    }
}
