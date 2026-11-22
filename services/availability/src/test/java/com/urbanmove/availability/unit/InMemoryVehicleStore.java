package com.urbanmove.availability.unit;

import com.urbanmove.availability.application.ConcurrentUpdateException;
import com.urbanmove.availability.application.DuplicateEventException;
import com.urbanmove.availability.application.EventMetadata;
import com.urbanmove.availability.application.EventOutcome;
import com.urbanmove.availability.application.port.ProcessedEvents;
import com.urbanmove.availability.application.port.VehicleChange;
import com.urbanmove.availability.application.port.VehiclePage;
import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.VehicleSearch;
import com.urbanmove.availability.application.port.VehicleWriter;
import com.urbanmove.availability.application.port.ZoneSummary;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Almacén en memoria con las mismas condiciones que el repositorio DynamoDB. */
final class InMemoryVehicleStore implements VehicleReader, VehicleWriter, ProcessedEvents {
    private final Map<VehicleId, VehicleAvailability.Snapshot> vehicles = new HashMap<>();
    final Map<UUID, EventOutcome> processed = new LinkedHashMap<>();

    @Override
    public synchronized Optional<VehicleAvailability> findById(VehicleId id) {
        return Optional.ofNullable(vehicles.get(id)).map(VehicleAvailability::rehydrate);
    }

    @Override
    public synchronized VehiclePage search(VehicleSearch search) {
        List<VehicleAvailability> found = vehicles.values().stream()
                .filter(v -> v.status() == search.status())
                .filter(v -> search.zoneId() == null || search.zoneId().equals(v.zoneId()))
                .filter(v -> search.vehicleType() == null || search.vehicleType() == v.vehicleType())
                .sorted(Comparator.comparing(v -> v.vehicleId().value()))
                .limit(search.limit())
                .map(VehicleAvailability::rehydrate)
                .toList();
        return new VehiclePage(found, null);
    }

    @Override
    public synchronized List<ZoneSummary> summary(ZoneId zoneId, VehicleType vehicleType) {
        Map<String, Map<VehicleStatus, Integer>> counts = new HashMap<>();
        vehicles.values().stream()
                .filter(v -> zoneId == null || zoneId.equals(v.zoneId()))
                .filter(v -> vehicleType == null || vehicleType == v.vehicleType())
                .forEach(v -> counts.computeIfAbsent(v.zoneId().value() + "#" + v.vehicleType(),
                        k -> new EnumMap<>(VehicleStatus.class)).merge(v.status(), 1, Integer::sum));
        List<ZoneSummary> result = new ArrayList<>();
        counts.forEach((key, value) -> {
            String[] parts = key.split("#");
            result.add(new ZoneSummary(new ZoneId(parts[0]), VehicleType.valueOf(parts[1]), value));
        });
        return result;
    }

    @Override
    public synchronized void apply(List<VehicleChange> changes, EventMetadata event) {
        if (processed.containsKey(event.eventId())) {
            throw new DuplicateEventException(event.eventId());
        }
        for (VehicleChange change : changes) {
            VehicleAvailability.Snapshot stored = vehicles.get(change.vehicle().vehicleId());
            boolean ok = change.expectedVersion() == null ? stored == null
                    : stored != null && stored.version() == change.expectedVersion();
            if (!ok) {
                throw new ConcurrentUpdateException("Vehicle " + change.vehicle().vehicleId() + " changed");
            }
        }
        for (VehicleChange change : changes) {
            VehicleAvailability.Snapshot s = change.vehicle().snapshot();
            long version = change.expectedVersion() == null ? 0 : change.expectedVersion() + 1;
            vehicles.put(s.vehicleId(), new VehicleAvailability.Snapshot(s.vehicleId(), s.vehicleType(), s.zoneId(),
                    s.status(), s.batteryLow(), event.occurredAt(), version));
        }
        processed.put(event.eventId(), EventOutcome.APPLIED);
    }

    @Override
    public synchronized void markProcessed(EventMetadata event, EventOutcome outcome) {
        processed.putIfAbsent(event.eventId(), outcome);
    }

    @Override
    public synchronized boolean registerIfAbsent(VehicleAvailability vehicle) {
        return vehicles.putIfAbsent(vehicle.vehicleId(), vehicle.snapshot()) == null;
    }

    @Override
    public synchronized boolean isProcessed(UUID eventId) {
        return processed.containsKey(eventId);
    }

    synchronized void bumpVersion(VehicleId id) {
        VehicleAvailability.Snapshot s = vehicles.get(id);
        vehicles.put(id, new VehicleAvailability.Snapshot(s.vehicleId(), s.vehicleType(), s.zoneId(), s.status(),
                s.batteryLow(), s.updatedAt(), s.version() + 1));
    }
}
