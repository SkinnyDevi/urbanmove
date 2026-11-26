package com.urbanmove.inventory.infrastructure.dynamo;

import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.NOT_EXISTS;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.failed;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.n;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.put;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.putVersioned;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.s;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.EXPIRES_AT;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.PK;

import com.urbanmove.inventory.application.ConcurrentUpdateException;
import com.urbanmove.inventory.application.DuplicateEventException;
import com.urbanmove.inventory.application.EventMetadata;
import com.urbanmove.inventory.application.EventOutcome;
import com.urbanmove.inventory.application.port.AssetEventWriter;
import com.urbanmove.inventory.domain.VehicleAsset;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Escribe el efecto de un evento del bus y su marca PROCESSED en la misma transacción. */
public final class DynamoAssetEventWriter implements AssetEventWriter {
    private static final Duration PROCESSED_TTL = Duration.ofDays(7);

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;

    public DynamoAssetEventWriter(DynamoDbClient dynamo, String table, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void register(VehicleAsset asset, EventMetadata event) {
        write(put(table, AssetItems.toItem(asset, 0), NOT_EXISTS, null), event, asset);
    }

    @Override
    public void update(VehicleAsset asset, long expectedVersion, EventMetadata event) {
        write(putVersioned(table, AssetItems.toItem(asset, expectedVersion + 1), expectedVersion), event, asset);
    }

    @Override
    public void markProcessed(EventMetadata event, EventOutcome outcome) {
        try {
            dynamo.putItem(r -> r.tableName(table).item(processedItem(event, outcome)).conditionExpression(NOT_EXISTS));
        } catch (ConditionalCheckFailedException alreadyMarked) {
            throw new DuplicateEventException(event.eventId());
        }
    }

    @Override
    public boolean registerIfAbsent(VehicleAsset asset) {
        try {
            dynamo.putItem(r -> r.tableName(table).item(AssetItems.toItem(asset, 0)).conditionExpression(NOT_EXISTS));
            return true;
        } catch (ConditionalCheckFailedException exists) {
            return false;
        }
    }

    private void write(TransactWriteItem change, EventMetadata event, VehicleAsset asset) {
        TransactWriteItem marker = put(table, processedItem(event, EventOutcome.APPLIED), NOT_EXISTS, null);
        try {
            dynamo.transactWriteItems(r -> r.transactItems(change, marker));
        } catch (TransactionCanceledException e) {
            if (failed(e, 1)) {
                throw new DuplicateEventException(event.eventId());
            }
            if (failed(e, 0)) {
                throw new ConcurrentUpdateException(asset.vehicleId());
            }
            throw e;
        }
    }

    private Map<String, AttributeValue> processedItem(EventMetadata event, EventOutcome outcome) {
        return Map.of(PK, s(InventoryTable.processedKey(event.eventId().toString())),
                "eventType", s(event.eventType()),
                "outcome", s(outcome.name()),
                EXPIRES_AT, n(clock.instant().plus(PROCESSED_TTL).getEpochSecond()));
    }
}
