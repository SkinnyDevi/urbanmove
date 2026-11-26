package com.urbanmove.inventory.application;

import com.urbanmove.inventory.application.port.EventDispatcher;
import com.urbanmove.inventory.application.port.IdempotencyRecord;
import com.urbanmove.inventory.application.port.IdempotencyStore;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.application.port.RetirementWriter;
import com.urbanmove.inventory.application.port.VehicleAssetReader;
import com.urbanmove.inventory.domain.VehicleAsset;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: dar de baja definitiva un vehículo retirado de la calle y publicar VehicleRetired. */
public final class RetireVehicleUseCase {
    private static final Logger log = LoggerFactory.getLogger(RetireVehicleUseCase.class);

    private final VehicleAssetReader reader;
    private final RetirementWriter writer;
    private final IdempotencyStore idempotency;
    private final EventDispatcher dispatcher;
    private final Supplier<UUID> eventIds;
    private final Clock clock;

    public RetireVehicleUseCase(VehicleAssetReader reader, RetirementWriter writer, IdempotencyStore idempotency,
                                EventDispatcher dispatcher, Supplier<UUID> eventIds, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.eventIds = Objects.requireNonNull(eventIds);
        this.clock = Objects.requireNonNull(clock);
    }

    public CommandResult execute(RetireCommand command) {
        String operation = "RETIRE:" + command.vehicleId().value();
        Optional<CommandResult> done = previous(command.idempotencyKey(), operation);
        if (done.isPresent()) {
            return done.get();
        }
        VehicleAsset asset = reader.findById(command.vehicleId())
                .orElseThrow(() -> new VehicleNotFoundException(command.vehicleId()));
        long expectedVersion = asset.version();
        asset.retire(command.reason(), clock.instant());
        List<OutgoingEvent> events = asset.pullDomainEvents().stream()
                .map(event -> new OutgoingEvent(eventIds.get(), command.correlationId(), event))
                .toList();
        try {
            writer.retire(asset, expectedVersion,
                    new IdempotencyRecord(command.idempotencyKey(), operation, command.vehicleId()), events);
        } catch (DuplicateRequestException e) {
            return previous(command.idempotencyKey(), operation).orElseThrow(() -> e);
        }
        log.atInfo().addKeyValue("vehicleId", command.vehicleId().value()).log("Vehicle retired");
        dispatcher.dispatch(events);
        return new CommandResult(asset, false);
    }

    private Optional<CommandResult> previous(String key, String operation) {
        return idempotency.find(key).map(record -> {
            if (!operation.equals(record.operation())) {
                throw new IdempotencyKeyReusedException(key);
            }
            log.atInfo().addKeyValue("vehicleId", record.vehicleId().value()).log("Duplicate request ignored");
            return new CommandResult(reader.findById(record.vehicleId())
                    .orElseThrow(() -> new VehicleNotFoundException(record.vehicleId())), true);
        });
    }
}
