package com.urbanmove.maintenance.domain;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.event.DomainEvent;
import com.urbanmove.maintenance.domain.event.VehicleReturnedToService;
import com.urbanmove.maintenance.domain.event.VehicleWithdrawnForMaintenance;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Agregado orden de mantenimiento: ciclo retirada → carga/reparación/revisión → vuelta al servicio.
 */
public final class MaintenanceOrder {
    public static final int MAX_NOTES = 1000;
    public static final int MAX_REASON = 500;

    public record Snapshot(MaintenanceOrderId orderId, VehicleId vehicleId, VehicleType vehicleType,
                           MaintenanceReason reason, ZoneId zoneId, MaintenanceOrderStatus status,
                           TechnicianId technicianId, Set<MaintenanceWork> workPerformed, String notes,
                           UUID triggeredByEventId, Instant createdAt, Instant withdrawnAt, Instant returnedAt,
                           ZoneId returnZoneId, String cancellationReason, long version) {
        public Snapshot {
            workPerformed = workPerformed == null || workPerformed.isEmpty()
                    ? Set.of() : Set.copyOf(EnumSet.copyOf(workPerformed));
        }
    }

    private final MaintenanceOrderId orderId;
    private final VehicleId vehicleId;
    private final VehicleType vehicleType;
    private final MaintenanceReason reason;
    private final ZoneId zoneId;
    private final String notes;
    private final UUID triggeredByEventId;
    private final Instant createdAt;
    private final long version;
    private final Set<MaintenanceWork> workPerformed;
    private MaintenanceOrderState state;
    private TechnicianId technicianId;
    private Instant withdrawnAt;
    private Instant returnedAt;
    private ZoneId returnZoneId;
    private String cancellationReason;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private MaintenanceOrder(Snapshot s) {
        this.orderId = Objects.requireNonNull(s.orderId(), "orderId");
        this.vehicleId = Objects.requireNonNull(s.vehicleId(), "vehicleId");
        this.vehicleType = Objects.requireNonNull(s.vehicleType(), "vehicleType");
        this.reason = Objects.requireNonNull(s.reason(), "reason");
        this.zoneId = Objects.requireNonNull(s.zoneId(), "zoneId");
        this.createdAt = Objects.requireNonNull(s.createdAt(), "createdAt");
        this.state = MaintenanceOrderState.of(Objects.requireNonNull(s.status(), "status"));
        this.notes = checkLength(s.notes(), MAX_NOTES, "notes");
        this.triggeredByEventId = s.triggeredByEventId();
        this.technicianId = s.technicianId();
        this.withdrawnAt = s.withdrawnAt();
        this.returnedAt = s.returnedAt();
        this.returnZoneId = s.returnZoneId();
        this.cancellationReason = s.cancellationReason();
        this.version = s.version();
        this.workPerformed = s.workPerformed().isEmpty()
                ? EnumSet.noneOf(MaintenanceWork.class) : EnumSet.copyOf(s.workPerformed());
    }

    public static MaintenanceOrder open(MaintenanceOrderId orderId, VehicleId vehicleId, VehicleType vehicleType,
                                        MaintenanceReason reason, ZoneId zoneId, Instant createdAt,
                                        String notes, UUID triggeredByEventId) {
        return new MaintenanceOrder(new Snapshot(orderId, vehicleId, vehicleType, reason, zoneId,
                MaintenanceOrderStatus.PENDING, null, Set.of(), notes, triggeredByEventId, createdAt,
                null, null, null, null, 0));
    }

    public static MaintenanceOrder rehydrate(Snapshot snapshot) {
        return new MaintenanceOrder(snapshot);
    }

    public Snapshot snapshot() {
        return new Snapshot(orderId, vehicleId, vehicleType, reason, zoneId, state.status(), technicianId,
                workPerformed, notes, triggeredByEventId, createdAt, withdrawnAt, returnedAt, returnZoneId,
                cancellationReason, version);
    }

    public void withdrawForMaintenance(TechnicianId technician, Instant at) {
        Objects.requireNonNull(technician, "technician");
        Objects.requireNonNull(at, "at");
        state = state.withdrawForMaintenance();
        technicianId = technician;
        withdrawnAt = at;
        pendingEvents.add(new VehicleWithdrawnForMaintenance(vehicleId, vehicleType, orderId, reason, zoneId, technician, at));
    }

    public void startCharging() {
        state = state.startCharging();
        workPerformed.add(MaintenanceWork.RECHARGE);
    }

    public void startRepair() {
        state = state.startRepair();
        workPerformed.add(MaintenanceWork.REPAIR);
    }

    public void markReadyForService() {
        state = state.markReadyForService();
        if (workPerformed.isEmpty()) {
            workPerformed.add(MaintenanceWork.INSPECTION);
        }
    }

    public void returnToService(ZoneId zone, Instant at) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(at, "at");
        state = state.returnToService();
        returnedAt = at;
        returnZoneId = zone;
        long outOfServiceSeconds = Math.max(0, Duration.between(withdrawnAt, at).toSeconds());
        pendingEvents.add(new VehicleReturnedToService(vehicleId, vehicleType, orderId,
                List.copyOf(workPerformed), zone, outOfServiceSeconds, at));
    }

    public void cancel(String reasonText) {
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        checkLength(reasonText, MAX_REASON, "cancellation reason");
        state = state.cancel();
        cancellationReason = reasonText;
    }

    public boolean isActive() {
        MaintenanceOrderStatus status = state.status();
        return status != MaintenanceOrderStatus.RETURNED && status != MaintenanceOrderStatus.CANCELLED;
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    private static String checkLength(String text, int max, String name) {
        if (text != null && text.length() > max) {
            throw new IllegalArgumentException(name + " cannot be longer than " + max + " characters");
        }
        return text;
    }

    public MaintenanceOrderId orderId() { return orderId; }

    public VehicleId vehicleId() { return vehicleId; }

    public MaintenanceOrderStatus status() { return state.status(); }

    public long version() { return version; }

    public Set<MaintenanceWork> workPerformed() { return Set.copyOf(workPerformed); }
}
