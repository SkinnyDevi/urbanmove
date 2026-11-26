package com.urbanmove.inventory.infrastructure.dynamo;

import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.NOT_EXISTS;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.failed;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.n;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.put;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.putVersioned;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.s;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.EXPIRES_AT;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_PK;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_SK;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.PK;

import com.urbanmove.inventory.application.ConcurrentUpdateException;
import com.urbanmove.inventory.application.DuplicateRequestException;
import com.urbanmove.inventory.application.port.IdempotencyRecord;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.application.port.RetirementWriter;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.infrastructure.events.EventEnvelopes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Guarda una baja, su clave de idempotencia y el evento en la bandeja de salida, en una transacción. */
public final class DynamoRetirementWriter implements RetirementWriter {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(7);

    private final DynamoDbClient dynamo;
    private final String table;
    private final EventEnvelopes envelopes;
    private final Clock clock;

    public DynamoRetirementWriter(DynamoDbClient dynamo, String table, EventEnvelopes envelopes, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.envelopes = Objects.requireNonNull(envelopes);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void retire(VehicleAsset asset, long expectedVersion, IdempotencyRecord idempotency,
                       List<OutgoingEvent> events) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(putVersioned(table, AssetItems.toItem(asset, expectedVersion + 1), expectedVersion));
        items.add(put(table, idempotencyItem(idempotency), NOT_EXISTS, null));
        events.forEach(event -> items.add(put(table, outboxItem(event), null, null)));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            if (failed(e, 1)) {
                throw new DuplicateRequestException(idempotency.key());
            }
            if (failed(e, 0)) {
                throw new ConcurrentUpdateException(asset.vehicleId());
            }
            throw e;
        }
    }

    private Map<String, AttributeValue> idempotencyItem(IdempotencyRecord record) {
        return Map.of(PK, s(InventoryTable.idempotencyKey(record.key())), "operation", s(record.operation()),
                "vehicleId", s(record.vehicleId().value()),
                EXPIRES_AT, n(clock.instant().plus(IDEMPOTENCY_TTL).getEpochSecond()));
    }

    private Map<String, AttributeValue> outboxItem(OutgoingEvent event) {
        return Map.of(PK, s(InventoryTable.outboxKey(event.eventId().toString())),
                GSI1_PK, s(InventoryTable.OUTBOX_PARTITION),
                GSI1_SK, s(EventEnvelopes.timestamp(event.event().occurredAt()) + "#" + event.eventId()),
                "eventId", s(event.eventId().toString()),
                "eventType", s(event.event().eventType()),
                "envelope", s(envelopes.toJson(event)));
    }
}
