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
import java.util.Optional;
import java.util.Set;

/**
 * Agregado orden de mantenimiento: ciclo retirada → carga/reparación/revisión → vuelta al servicio.
 *
 * <p>Las transiciones las decide {@link MaintenanceOrderState}. Al retirar y al devolver el
 * vehículo, la orden registra el evento de dominio correspondiente; el caso de uso los recoge
 * con {@link #pullDomainEvents()} después de guardar y los publica (AGENTS.md 5.2).
 */
public final class MaintenanceOrder {

    private final MaintenanceOrderId orderId;
    private final VehicleId vehicleId;
    private final VehicleType vehicleType;
    private final MaintenanceReason reason;
    private final ZoneId zoneId;
    private MaintenanceOrderState state;
    private TechnicianId technicianId;
    private Instant withdrawnAt;
    private final Set<MaintenanceWork> workPerformed;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private MaintenanceOrder(MaintenanceOrderId orderId, VehicleId vehicleId, VehicleType vehicleType,
                             MaintenanceReason reason, ZoneId zoneId, MaintenanceOrderState state,
                             TechnicianId technicianId, Instant withdrawnAt, Set<MaintenanceWork> workPerformed) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.state = Objects.requireNonNull(state, "state");
        this.technicianId = technicianId;
        this.withdrawnAt = withdrawnAt;
        this.workPerformed = workPerformed.isEmpty() ? EnumSet.noneOf(MaintenanceWork.class) : EnumSet.copyOf(workPerformed);
    }

    /** Orden nueva: el vehículo todavía está en la calle, en {@code zoneId}. */
    public static MaintenanceOrder open(MaintenanceOrderId orderId, VehicleId vehicleId, VehicleType vehicleType,
                                        MaintenanceReason reason, ZoneId zoneId) {
        return new MaintenanceOrder(orderId, vehicleId, vehicleType, reason, zoneId,
                MaintenanceOrderState.Pending.INSTANCE, null, null, Set.of());
    }

    /** Reconstrucción desde la base de datos (solo la usa el repositorio). */
    public static MaintenanceOrder rehydrate(MaintenanceOrderId orderId, VehicleId vehicleId, VehicleType vehicleType,
                                             MaintenanceReason reason, ZoneId zoneId, MaintenanceOrderStatus status,
                                             TechnicianId technicianId, Instant withdrawnAt,
                                             Set<MaintenanceWork> workPerformed) {
        Objects.requireNonNull(status, "status");
        return new MaintenanceOrder(orderId, vehicleId, vehicleType, reason, zoneId,
                MaintenanceOrderState.of(status), technicianId, withdrawnAt, workPerformed);
    }

    /** El técnico recoge el vehículo de la calle. Publica VehicleWithdrawnForMaintenance. */
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

    /** Si no se cargó ni se reparó, el trabajo realizado fue una revisión. */
    public void markReadyForService() {
        state = state.markReadyForService();
        if (workPerformed.isEmpty()) {
            workPerformed.add(MaintenanceWork.INSPECTION);
        }
    }

    /** El vehículo vuelve a la calle en {@code zone}. Publica VehicleReturnedToService. */
    public void returnToService(ZoneId zone, Instant at) {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(at, "at");
        state = state.returnToService();
        long outOfServiceSeconds = Math.max(0, Duration.between(withdrawnAt, at).toSeconds());
        pendingEvents.add(new VehicleReturnedToService(vehicleId, vehicleType, orderId,
                List.copyOf(workPerformed), zone, outOfServiceSeconds, at));
    }

    /** Solo antes de retirar el vehículo (p. ej. orden duplicada o falsa alarma). */
    public void cancel() {
        state = state.cancel();
    }

    /** Devuelve los eventos registrados desde la última llamada y vacía la lista. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public MaintenanceOrderId orderId() { return orderId; }

    public VehicleId vehicleId() { return vehicleId; }

    public VehicleType vehicleType() { return vehicleType; }

    public MaintenanceReason reason() { return reason; }

    public ZoneId zoneId() { return zoneId; }

    public MaintenanceOrderStatus status() { return state.status(); }

    public Optional<TechnicianId> technicianId() { return Optional.ofNullable(technicianId); }

    public Optional<Instant> withdrawnAt() { return Optional.ofNullable(withdrawnAt); }

    public Set<MaintenanceWork> workPerformed() { return Set.copyOf(workPerformed); }
}
