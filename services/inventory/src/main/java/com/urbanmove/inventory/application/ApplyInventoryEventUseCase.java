package com.urbanmove.inventory.application;

import com.urbanmove.inventory.application.port.AssetEventWriter;
import com.urbanmove.inventory.application.port.ProcessedEvents;
import com.urbanmove.inventory.application.port.VehicleAssetReader;
import com.urbanmove.inventory.domain.InvalidStateTransitionError;
import com.urbanmove.inventory.domain.VehicleAsset;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: aplicar un evento del bus al registro de activos (consumidor idempotente). */
public final class ApplyInventoryEventUseCase {
    private static final Logger log = LoggerFactory.getLogger(ApplyInventoryEventUseCase.class);

    private final VehicleAssetReader reader;
    private final AssetEventWriter writer;
    private final ProcessedEvents processed;

    public ApplyInventoryEventUseCase(VehicleAssetReader reader, AssetEventWriter writer, ProcessedEvents processed) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.processed = Objects.requireNonNull(processed);
    }

    public EventOutcome execute(IncomingEvent incoming) {
        EventMetadata meta = incoming.metadata();
        if (processed.isProcessed(meta.eventId())) {
            return duplicate(meta);
        }
        try {
            apply(incoming.event(), meta);
        } catch (InvalidStateTransitionError e) {
            writer.markProcessed(meta, EventOutcome.REJECTED);
            log.atWarn().addKeyValue("eventId", meta.eventId().toString())
                    .addKeyValue("eventType", meta.eventType())
                    .addKeyValue("currentState", e.from().name())
                    .addKeyValue("action", e.action().name())
                    .log("Event rejected by the asset state machine");
            return EventOutcome.REJECTED;
        } catch (DuplicateEventException e) {
            return duplicate(meta);
        }
        log.atInfo().addKeyValue("eventId", meta.eventId().toString())
                .addKeyValue("eventType", meta.eventType())
                .addKeyValue("vehicleId", incoming.event().vehicleId().value())
                .log("Inventory updated");
        return EventOutcome.APPLIED;
    }

    private void apply(InventoryEvent event, EventMetadata meta) {
        Optional<VehicleAsset> existing = reader.findById(event.vehicleId());
        switch (event) {
            case InventoryEvent.Purchased p -> {
                if (existing.isPresent()) {
                    writer.markProcessed(meta, EventOutcome.APPLIED);
                } else {
                    writer.register(VehicleAsset.registerPurchased(p.vehicleId(), p.vehicleType(), p.model(),
                            p.purchaseDate(), p.purchasePrice(), meta.occurredAt()), meta);
                }
            }
            case InventoryEvent.Withdrawn w -> update(existing, w, meta, VehicleAsset::markWithdrawn);
            case InventoryEvent.ReturnedToService r -> update(existing, r, meta, VehicleAsset::markReturnedToService);
        }
    }

    private void update(Optional<VehicleAsset> existing, InventoryEvent event, EventMetadata meta,
                        java.util.function.Consumer<VehicleAsset> action) {
        VehicleAsset asset = existing.orElseThrow(() -> new UnknownVehicleException(event.vehicleId()));
        long expectedVersion = asset.version();
        action.accept(asset);
        writer.update(asset, expectedVersion, meta);
    }

    private static EventOutcome duplicate(EventMetadata meta) {
        log.atInfo().addKeyValue("eventId", meta.eventId().toString())
                .addKeyValue("eventType", meta.eventType())
                .log("Duplicate event ignored");
        return EventOutcome.DUPLICATE;
    }
}
