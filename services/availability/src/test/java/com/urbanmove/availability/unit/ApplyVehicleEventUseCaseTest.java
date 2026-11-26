package com.urbanmove.availability.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.availability.application.ApplyVehicleEventUseCase;
import com.urbanmove.availability.application.ConcurrentUpdateException;
import com.urbanmove.availability.application.EventMetadata;
import com.urbanmove.availability.application.EventOutcome;
import com.urbanmove.availability.application.GetVehicleUseCase;
import com.urbanmove.availability.application.IncomingEvent;
import com.urbanmove.availability.application.ListVehiclesUseCase;
import com.urbanmove.availability.application.SeedFleetUseCase;
import com.urbanmove.availability.application.UnknownVehicleException;
import com.urbanmove.availability.application.VehicleEvent;
import com.urbanmove.availability.application.ZoneSummaryUseCase;
import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.VehicleSearch;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Aplicación de eventos al vehículo: cambios, duplicados, rechazos y reintentos. */
class ApplyVehicleEventUseCaseTest {
    private static final VehicleId VEHICLE = new VehicleId("veh-0042");
    private static final ZoneId TAFIRA = new ZoneId("tafira");
    private static final ZoneId MESA = new ZoneId("mesa-y-lopez");
    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    private InMemoryVehicleStore store;
    private ApplyVehicleEventUseCase apply;

    @BeforeEach
    void setUp() {
        store = new InMemoryVehicleStore();
        apply = new ApplyVehicleEventUseCase(store, store, store);
    }

    private static IncomingEvent event(String type, VehicleEvent event) {
        return new IncomingEvent(new EventMetadata(UUID.randomUUID(), type, UUID.randomUUID().toString(), T0), event);
    }

    private EventOutcome purchase(VehicleId id, ZoneId zone) {
        return apply.execute(event("VehiclePurchased", new VehicleEvent.Purchased(id, VehicleType.SCOOTER, zone)));
    }

    private VehicleAvailability vehicle() {
        return new GetVehicleUseCase(store).execute(VEHICLE);
    }

    @Test
    void aPurchasedVehicleBecomesAvailableInItsZone() {
        assertEquals(EventOutcome.APPLIED, purchase(VEHICLE, TAFIRA));

        assertEquals(VehicleStatus.AVAILABLE, vehicle().status());
        assertEquals(TAFIRA, vehicle().zoneId());
        assertEquals(0, vehicle().version());
    }

    @Test
    void theFullUserAndMaintenanceCycleIsApplied() {
        purchase(VEHICLE, TAFIRA);

        apply.execute(event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE)));
        assertEquals(VehicleStatus.RESERVED, vehicle().status());
        apply.execute(event("RideStarted", new VehicleEvent.RideStarted(VEHICLE)));
        apply.execute(event("VehicleBatteryLow", new VehicleEvent.BatteryLow(VEHICLE)));
        apply.execute(event("RideFinished", new VehicleEvent.RideFinished(VEHICLE, MESA)));
        assertEquals(VehicleStatus.LOW_BATTERY, vehicle().status(), "batería baja durante el viaje");
        assertEquals(MESA, vehicle().zoneId());
        apply.execute(event("VehicleWithdrawnForMaintenance", new VehicleEvent.WithdrawnForMaintenance(VEHICLE)));
        apply.execute(event("VehicleReturnedToService", new VehicleEvent.ReturnedToService(VEHICLE, TAFIRA)));

        assertEquals(VehicleStatus.AVAILABLE, vehicle().status());
        assertEquals(TAFIRA, vehicle().zoneId());
        assertFalse(vehicle().batteryLow());
        assertEquals(6, vehicle().version(), "una versión por cada cambio");
    }

    @Test
    void aRepeatedEventIsIgnored() {
        purchase(VEHICLE, TAFIRA);
        IncomingEvent reserved = event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE));
        apply.execute(reserved);
        apply.execute(event("ReservationCancelled", new VehicleEvent.ReservationReleased(VEHICLE)));

        assertEquals(EventOutcome.DUPLICATE, apply.execute(reserved));

        assertEquals(VehicleStatus.AVAILABLE, vehicle().status(), "la reserva repetida no vuelve a reservarlo");
    }

    @Test
    void anIllegalEventIsRejectedAndRecordedSoItIsNotRetried() {
        purchase(VEHICLE, TAFIRA);
        IncomingEvent finished = event("RideFinished", new VehicleEvent.RideFinished(VEHICLE, MESA));

        assertEquals(EventOutcome.REJECTED, apply.execute(finished));

        assertEquals(VehicleStatus.AVAILABLE, vehicle().status());
        assertEquals(TAFIRA, vehicle().zoneId());
        assertEquals(EventOutcome.REJECTED, store.processed.get(finished.metadata().eventId()));
        assertEquals(EventOutcome.DUPLICATE, apply.execute(finished));
    }

    @Test
    void aRetiredVehicleIsNeverOfferedAgain() {
        purchase(VEHICLE, TAFIRA);
        IncomingEvent retired = event("VehicleRetired", new VehicleEvent.Retired(VEHICLE));

        assertEquals(EventOutcome.REJECTED, apply.execute(retired), "solo se da de baja desde WITHDRAWN");
        apply.execute(event("VehicleWithdrawnForMaintenance", new VehicleEvent.WithdrawnForMaintenance(VEHICLE)));
        assertEquals(EventOutcome.APPLIED, apply.execute(event("VehicleRetired", new VehicleEvent.Retired(VEHICLE))));

        assertEquals(VehicleStatus.RETIRED, vehicle().status());
        assertFalse(vehicle().isReservable());
        assertEquals(EventOutcome.REJECTED,
                apply.execute(event("VehicleReturnedToService", new VehicleEvent.ReturnedToService(VEHICLE, MESA))));
    }

    @Test
    void anEventForAnUnknownVehicleFailsSoSqsRetriesIt() {
        IncomingEvent reserved = event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE));

        assertThrows(UnknownVehicleException.class, () -> apply.execute(reserved));
        assertFalse(store.isProcessed(reserved.metadata().eventId()), "no se marca: debe poder reintentarse");
    }

    @Test
    void aPurchaseOfAnExistingVehicleChangesNothing() {
        purchase(VEHICLE, TAFIRA);
        apply.execute(event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE)));

        assertEquals(EventOutcome.APPLIED, purchase(VEHICLE, MESA));

        assertEquals(VehicleStatus.RESERVED, vehicle().status());
        assertEquals(TAFIRA, vehicle().zoneId());
    }

    @Test
    void aRelocationMovesEveryVehicle() {
        VehicleId other = new VehicleId("veh-0043");
        purchase(VEHICLE, TAFIRA);
        purchase(other, TAFIRA);

        apply.execute(event("RelocationOrderCompleted", new VehicleEvent.Relocated(List.of(VEHICLE, other), MESA)));

        assertEquals(MESA, vehicle().zoneId());
        assertEquals(MESA, new GetVehicleUseCase(store).execute(other).zoneId());
    }

    @Test
    void aConcurrentChangeIsDetectedSoSqsRetriesTheEvent() {
        purchase(VEHICLE, TAFIRA);
        VehicleReader racing = new VehicleReader() {
            @Override
            public Optional<VehicleAvailability> findById(VehicleId id) {
                Optional<VehicleAvailability> read = store.findById(id);
                store.bumpVersion(id);
                return read;
            }

            @Override
            public com.urbanmove.availability.application.port.VehiclePage search(VehicleSearch search) {
                return store.search(search);
            }

            @Override
            public List<com.urbanmove.availability.application.port.ZoneSummary> summary(ZoneId z, VehicleType t) {
                return store.summary(z, t);
            }
        };
        ApplyVehicleEventUseCase racingApply = new ApplyVehicleEventUseCase(racing, store, store);

        assertThrows(ConcurrentUpdateException.class,
                () -> racingApply.execute(event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE))));
    }

    @Test
    void theInitialFleetIsLoadedOnceAndNeverOverwritten() {
        SeedFleetUseCase seed = new SeedFleetUseCase(
                () -> List.of(new VehicleEvent.Purchased(VEHICLE, VehicleType.BIKE, TAFIRA)), store,
                Clock.fixed(T0, ZoneOffset.UTC));
        assertEquals(1, seed.execute());
        apply.execute(event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE)));

        assertEquals(0, seed.execute());

        assertEquals(VehicleStatus.RESERVED, vehicle().status(), "reiniciar el servicio no libera vehículos");
    }

    @Test
    void queriesFilterByStatusZoneAndType() {
        purchase(VEHICLE, TAFIRA);
        purchase(new VehicleId("veh-0043"), MESA);
        apply.execute(event("VehicleReserved", new VehicleEvent.Reserved(VEHICLE)));

        var available = new ListVehiclesUseCase(store).execute(
                new VehicleSearch(VehicleStatus.AVAILABLE, null, null, 50, null));
        var summary = new ZoneSummaryUseCase(store).execute(TAFIRA, null);

        assertEquals(List.of("veh-0043"), available.items().stream().map(v -> v.vehicleId().value()).toList());
        assertEquals(1, summary.size());
        assertEquals(1, summary.get(0).counts().get(VehicleStatus.RESERVED));
        assertEquals(0, summary.get(0).counts().get(VehicleStatus.AVAILABLE));
    }
}
