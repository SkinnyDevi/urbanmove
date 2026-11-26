package com.urbanmove.inventory.unit;

import com.urbanmove.inventory.application.ConcurrentUpdateException;
import com.urbanmove.inventory.application.DuplicateEventException;
import com.urbanmove.inventory.application.DuplicateRequestException;
import com.urbanmove.inventory.application.EventMetadata;
import com.urbanmove.inventory.application.EventOutcome;
import com.urbanmove.inventory.application.port.AssetEventWriter;
import com.urbanmove.inventory.application.port.IdempotencyRecord;
import com.urbanmove.inventory.application.port.IdempotencyStore;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.application.port.ProcessedEvents;
import com.urbanmove.inventory.application.port.RetirementWriter;
import com.urbanmove.inventory.application.port.VehicleAssetReader;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Almacén en memoria con las mismas condiciones que los adaptadores de DynamoDB. */
final class InMemoryInventoryStore
        implements VehicleAssetReader, AssetEventWriter, RetirementWriter, ProcessedEvents, IdempotencyStore {
    private final Map<VehicleId, VehicleAsset.Snapshot> assets = new HashMap<>();
    final Map<UUID, EventOutcome> processed = new HashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    final List<OutgoingEvent> outbox = new ArrayList<>();

    @Override
    public synchronized Optional<VehicleAsset> findById(VehicleId id) {
        return Optional.ofNullable(assets.get(id)).map(VehicleAsset::rehydrate);
    }

    @Override
    public synchronized void register(VehicleAsset asset, EventMetadata event) {
        mark(event, EventOutcome.APPLIED);
        assets.put(asset.vehicleId(), withVersion(asset.snapshot(), 0));
    }

    @Override
    public synchronized void update(VehicleAsset asset, long expectedVersion, EventMetadata event) {
        checkVersion(asset, expectedVersion);
        mark(event, EventOutcome.APPLIED);
        assets.put(asset.vehicleId(), withVersion(asset.snapshot(), expectedVersion + 1));
    }

    @Override
    public synchronized void markProcessed(EventMetadata event, EventOutcome outcome) {
        mark(event, outcome);
    }

    @Override
    public synchronized boolean registerIfAbsent(VehicleAsset asset) {
        return assets.putIfAbsent(asset.vehicleId(), asset.snapshot()) == null;
    }

    @Override
    public synchronized void retire(VehicleAsset asset, long expectedVersion, IdempotencyRecord record,
                                    List<OutgoingEvent> events) {
        if (idempotency.containsKey(record.key())) {
            throw new DuplicateRequestException(record.key());
        }
        checkVersion(asset, expectedVersion);
        assets.put(asset.vehicleId(), withVersion(asset.snapshot(), expectedVersion + 1));
        idempotency.put(record.key(), record);
        outbox.addAll(events);
    }

    @Override
    public synchronized boolean isProcessed(UUID eventId) {
        return processed.containsKey(eventId);
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(idempotency.get(key));
    }

    synchronized void bumpVersion(VehicleId id) {
        assets.put(id, withVersion(assets.get(id), assets.get(id).version() + 1));
    }

    private void mark(EventMetadata event, EventOutcome outcome) {
        if (processed.putIfAbsent(event.eventId(), outcome) != null) {
            throw new DuplicateEventException(event.eventId());
        }
    }

    private void checkVersion(VehicleAsset asset, long expectedVersion) {
        VehicleAsset.Snapshot stored = assets.get(asset.vehicleId());
        if (stored == null || stored.version() != expectedVersion) {
            throw new ConcurrentUpdateException(asset.vehicleId());
        }
    }

    private static VehicleAsset.Snapshot withVersion(VehicleAsset.Snapshot s, long version) {
        return new VehicleAsset.Snapshot(s.vehicleId(), s.vehicleType(), s.model(), s.purchaseDate(), s.purchasePrice(),
                s.status(), s.registeredAt(), s.retiredAt(), s.retirementReason(), version);
    }
}
