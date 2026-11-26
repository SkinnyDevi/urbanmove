package com.urbanmove.availability.application;

import com.urbanmove.availability.application.port.ProcessedEvents;
import com.urbanmove.availability.application.port.VehicleChange;
import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.VehicleWriter;
import com.urbanmove.availability.domain.InvalidStateTransitionError;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: aplicar un evento del bus a la disponibilidad (consumidor idempotente). */
public final class ApplyVehicleEventUseCase {
    private static final Logger log = LoggerFactory.getLogger(ApplyVehicleEventUseCase.class);

    private final VehicleReader reader;
    private final VehicleWriter writer;
    private final ProcessedEvents processed;

    public ApplyVehicleEventUseCase(VehicleReader reader, VehicleWriter writer, ProcessedEvents processed) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.processed = Objects.requireNonNull(processed);
    }

    public EventOutcome execute(IncomingEvent incoming) {
        EventMetadata meta = incoming.metadata();
        if (processed.isProcessed(meta.eventId())) {
            return duplicate(meta);
        }

        List<VehicleChange> changes;
        try {
            changes = changesFor(incoming.event(), meta);
        } catch (InvalidStateTransitionError e) {
            writer.markProcessed(meta, EventOutcome.REJECTED);
            log.atWarn()
                    .addKeyValue("eventId", meta.eventId().toString())
                    .addKeyValue("eventType", meta.eventType())
                    .addKeyValue("currentState", e.from().name())
                    .addKeyValue("action", e.action().name())
                    .log("Event rejected by the vehicle state machine");
            return EventOutcome.REJECTED;
        }

        if (changes.isEmpty()) {
            writer.markProcessed(meta, EventOutcome.APPLIED);
            return EventOutcome.APPLIED;
        }
        try {
            writer.apply(changes, meta);
        } catch (DuplicateEventException e) {
            return duplicate(meta);
        }
        for (VehicleChange change : changes) {
            log.atInfo()
                    .addKeyValue("eventId", meta.eventId().toString())
                    .addKeyValue("eventType", meta.eventType())
                    .addKeyValue("vehicleId", change.vehicle().vehicleId().value())
                    .addKeyValue("status", change.vehicle().status().name())
                    .addKeyValue("zoneId", change.vehicle().zoneId().value())
                    .log("Vehicle availability updated");
        }
        return EventOutcome.APPLIED;
    }

    private List<VehicleChange> changesFor(VehicleEvent event, EventMetadata meta) {
        return switch (event) {
            case VehicleEvent.Purchased e -> reader.findById(e.vehicleId()).isPresent()
                    ? List.of()
                    : List.of(VehicleChange.created(VehicleAvailability.registerPurchased(
                            e.vehicleId(), e.vehicleType(), e.initialZone(), meta.occurredAt())));
            case VehicleEvent.Reserved e -> update(e.vehicleId(), VehicleAvailability::reserve);
            case VehicleEvent.ReservationReleased e -> update(e.vehicleId(), VehicleAvailability::releaseReservation);
            case VehicleEvent.RideStarted e -> update(e.vehicleId(), VehicleAvailability::startRide);
            case VehicleEvent.RideFinished e -> update(e.vehicleId(), v -> v.finishRide(e.destination()));
            case VehicleEvent.BatteryLow e -> update(e.vehicleId(), VehicleAvailability::reportLowBattery);
            case VehicleEvent.WithdrawnForMaintenance e -> update(e.vehicleId(), VehicleAvailability::withdrawForMaintenance);
            case VehicleEvent.ReturnedToService e -> update(e.vehicleId(), v -> v.returnToService(e.zone()));
            case VehicleEvent.Retired e -> update(e.vehicleId(), VehicleAvailability::retire);
            case VehicleEvent.Relocated e -> {
                List<VehicleChange> all = new ArrayList<>();
                for (VehicleId id : e.vehicleIds()) {
                    all.addAll(update(id, v -> v.relocateTo(e.targetZone())));
                }
                yield all;
            }
        };
    }

    private List<VehicleChange> update(VehicleId id, java.util.function.Consumer<VehicleAvailability> action) {
        VehicleAvailability vehicle = reader.findById(id).orElseThrow(() -> new UnknownVehicleException(id));
        VehicleChange change = VehicleChange.updated(vehicle);
        action.accept(vehicle);
        return List.of(change);
    }

    private static EventOutcome duplicate(EventMetadata meta) {
        log.atInfo()
                .addKeyValue("eventId", meta.eventId().toString())
                .addKeyValue("eventType", meta.eventType())
                .log("Duplicate event ignored");
        return EventOutcome.DUPLICATE;
    }
}
