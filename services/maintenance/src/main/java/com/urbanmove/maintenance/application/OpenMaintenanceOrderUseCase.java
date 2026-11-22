package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.application.port.IdempotencyRecord;
import com.urbanmove.maintenance.application.port.IdempotencyStore;
import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.MaintenanceOrderWriter;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: abrir una orden de mantenimiento (estado PENDING). */
public final class OpenMaintenanceOrderUseCase {
    static final String OPERATION = "OPEN";

    private static final Logger log = LoggerFactory.getLogger(OpenMaintenanceOrderUseCase.class);

    private final MaintenanceOrderReader reader;
    private final MaintenanceOrderWriter writer;
    private final IdempotencyStore idempotency;
    private final Supplier<MaintenanceOrderId> ids;
    private final Clock clock;

    public OpenMaintenanceOrderUseCase(MaintenanceOrderReader reader, MaintenanceOrderWriter writer,
                                       IdempotencyStore idempotency, Supplier<MaintenanceOrderId> ids, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    public CommandResult execute(OpenOrderCommand command) {
        Optional<CommandResult> previous = replay(command.idempotencyKey());
        if (previous.isPresent()) {
            return previous.get();
        }
        MaintenanceOrder order = MaintenanceOrder.open(ids.get(), command.vehicleId(), command.vehicleType(),
                command.reason(), command.zoneId(), clock.instant(), command.notes(), command.triggeredByEventId());
        try {
            writer.create(order, new IdempotencyRecord(command.idempotencyKey(), OPERATION, order.orderId()), List.of());
        } catch (DuplicateRequestException e) {
            return replay(command.idempotencyKey()).orElseThrow(() -> e);
        }
        log.atInfo()
                .addKeyValue("maintenanceOrderId", order.orderId().value())
                .addKeyValue("vehicleId", command.vehicleId().value())
                .addKeyValue("reason", command.reason().name())
                .log("Maintenance order opened");
        return new CommandResult(order, false);
    }

    private Optional<CommandResult> replay(String key) {
        return idempotency.find(key).map(record -> {
            if (!OPERATION.equals(record.operation())) {
                throw new IdempotencyKeyReusedException(key);
            }
            MaintenanceOrder order = reader.findById(record.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(record.orderId()));
            log.atInfo().addKeyValue("maintenanceOrderId", record.orderId().value())
                    .log("Duplicate request ignored");
            return new CommandResult(order, true);
        });
    }
}
