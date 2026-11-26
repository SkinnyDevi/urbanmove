package com.urbanmove.relocation.application;

import com.urbanmove.relocation.application.port.EventDispatcher;
import com.urbanmove.relocation.application.port.IdempotencyRecord;
import com.urbanmove.relocation.application.port.IdempotencyStore;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.application.port.RelocationOrderReader;
import com.urbanmove.relocation.application.port.RelocationOrderWriter;
import com.urbanmove.relocation.domain.RelocationOrder;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: asignar, empezar, completar o cancelar una orden de recolocación. */
public final class PerformRelocationActionUseCase {
    private static final Logger log = LoggerFactory.getLogger(PerformRelocationActionUseCase.class);

    private final RelocationOrderReader reader;
    private final RelocationOrderWriter writer;
    private final EventDispatcher dispatcher;
    private final PreviousResults previous;
    private final Supplier<UUID> eventIds;
    private final Clock clock;

    public PerformRelocationActionUseCase(RelocationOrderReader reader, RelocationOrderWriter writer,
                                          IdempotencyStore idempotency, EventDispatcher dispatcher,
                                          Supplier<UUID> eventIds, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.previous = new PreviousResults(idempotency, reader);
        this.eventIds = Objects.requireNonNull(eventIds);
        this.clock = Objects.requireNonNull(clock);
    }

    public CommandResult execute(RelocationCommand command) {
        String operation = command.action().name() + ":" + command.orderId().value();
        Optional<CommandResult> done = previous.find(command.idempotencyKey(), operation);
        if (done.isPresent()) {
            return done.get();
        }
        RelocationOrder order = reader.findById(command.orderId())
                .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
        long expectedVersion = order.version();
        apply(order, command);
        List<OutgoingEvent> events = order.pullDomainEvents().stream()
                .map(event -> new OutgoingEvent(eventIds.get(), command.correlationId(), event))
                .toList();
        try {
            writer.update(order, expectedVersion,
                    new IdempotencyRecord(command.idempotencyKey(), operation, command.orderId()), events);
        } catch (DuplicateRequestException e) {
            return previous.find(command.idempotencyKey(), operation).orElseThrow(() -> e);
        }
        log.atInfo().addKeyValue("relocationOrderId", command.orderId().value())
                .addKeyValue("action", command.action().name())
                .addKeyValue("status", order.status().name())
                .log("Relocation order updated");
        dispatcher.dispatch(events);
        return new CommandResult(order, false);
    }

    private void apply(RelocationOrder order, RelocationCommand command) {
        switch (command.action()) {
            case ASSIGN -> order.assign(command.technician());
            case START -> order.start(command.technician(), clock.instant());
            case COMPLETE -> order.complete(command.technician(), command.vehicleIds(), clock.instant());
            case CANCEL -> order.cancel(command.cancellationReason());
        }
    }
}
