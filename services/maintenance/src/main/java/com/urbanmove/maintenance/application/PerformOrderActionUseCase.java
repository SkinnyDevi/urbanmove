package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.application.port.EventDispatcher;
import com.urbanmove.maintenance.application.port.IdempotencyRecord;
import com.urbanmove.maintenance.application.port.IdempotencyStore;
import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.MaintenanceOrderWriter;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caso de uso: una acción del técnico sobre una orden (retirar, cargar, reparar, marcar lista,
 * devolver, cancelar).
 *
 * <p>Pasos: comprobar la Idempotency-Key, leer la orden, aplicar la acción (la máquina de estados
 * decide si es legal), guardar orden + eventos en una sola escritura y, después, publicar los
 * eventos (AGENTS.md 5.2). Si la publicación falla, los eventos siguen guardados y se reintentan.
 */
public final class PerformOrderActionUseCase {

    private static final Logger log = LoggerFactory.getLogger(PerformOrderActionUseCase.class);

    private final MaintenanceOrderReader reader;
    private final MaintenanceOrderWriter writer;
    private final IdempotencyStore idempotency;
    private final EventDispatcher dispatcher;
    private final Supplier<UUID> eventIds;
    private final Clock clock;

    public PerformOrderActionUseCase(MaintenanceOrderReader reader, MaintenanceOrderWriter writer,
                                     IdempotencyStore idempotency, EventDispatcher dispatcher,
                                     Supplier<UUID> eventIds, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.eventIds = Objects.requireNonNull(eventIds);
        this.clock = Objects.requireNonNull(clock);
    }

    public CommandResult execute(OrderActionCommand command) {
        String operation = command.action().name() + ":" + command.orderId().value();
        Optional<CommandResult> previous = replay(command, operation);
        if (previous.isPresent()) {
            return previous.get();
        }

        MaintenanceOrder order = reader.findById(command.orderId())
                .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
        long expectedVersion = order.version();
        apply(order, command);

        List<OutgoingEvent> events = order.pullDomainEvents().stream()
                .map(event -> new OutgoingEvent(eventIds.get(), command.correlationId(), event))
                .toList();
        try {
            writer.update(order, expectedVersion, new IdempotencyRecord(command.idempotencyKey(), operation,
                    command.orderId()), events);
        } catch (DuplicateRequestException e) {
            return replay(command, operation).orElseThrow(() -> e);
        }
        log.atInfo()
                .addKeyValue("maintenanceOrderId", command.orderId().value())
                .addKeyValue("action", command.action().name())
                .addKeyValue("status", order.status().name())
                .log("Maintenance order updated");

        dispatcher.dispatch(events);
        return new CommandResult(order, false);
    }

    /** Traduce el comando en la llamada al agregado. La legalidad la decide el agregado, no este método. */
    private void apply(MaintenanceOrder order, OrderActionCommand command) {
        switch (command.action()) {
            case WITHDRAW_FOR_MAINTENANCE -> order.withdrawForMaintenance(command.technician(), clock.instant());
            case START_CHARGING -> order.startCharging();
            case START_REPAIR -> order.startRepair();
            case MARK_READY_FOR_SERVICE -> order.markReadyForService();
            case RETURN_TO_SERVICE -> order.returnToService(command.returnZone(), clock.instant());
            case CANCEL -> order.cancel(command.cancellationReason());
        }
    }

    private Optional<CommandResult> replay(OrderActionCommand command, String operation) {
        return idempotency.find(command.idempotencyKey()).map(record -> {
            if (!operation.equals(record.operation())) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            MaintenanceOrder order = reader.findById(record.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(record.orderId()));
            log.atInfo().addKeyValue("maintenanceOrderId", record.orderId().value())
                    .log("Duplicate request ignored");
            return new CommandResult(order, true);
        });
    }
}
