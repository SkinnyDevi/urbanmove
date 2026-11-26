package com.urbanmove.relocation.application;

import com.urbanmove.relocation.application.port.IdempotencyStore;
import com.urbanmove.relocation.application.port.RelocationOrderReader;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Devuelve el resultado de una operación ya hecha con la misma clave de idempotencia. */
final class PreviousResults {
    private static final Logger log = LoggerFactory.getLogger(PreviousResults.class);

    private final IdempotencyStore idempotency;
    private final RelocationOrderReader reader;

    PreviousResults(IdempotencyStore idempotency, RelocationOrderReader reader) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.reader = Objects.requireNonNull(reader);
    }

    Optional<CommandResult> find(String key, String operation) {
        return idempotency.find(key).map(record -> {
            if (!operation.equals(record.operation())) {
                throw new IdempotencyKeyReusedException(key);
            }
            log.atInfo().addKeyValue("relocationOrderId", record.orderId().value()).log("Duplicate request ignored");
            return new CommandResult(reader.findById(record.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(record.orderId())), true);
        });
    }
}
