package com.urbanmove.inventory.infrastructure.dynamo;

import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.s;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.PK;

import com.urbanmove.inventory.application.port.IdempotencyRecord;
import com.urbanmove.inventory.application.port.IdempotencyStore;
import com.urbanmove.inventory.application.port.ProcessedEvents;
import com.urbanmove.inventory.application.port.VehicleAssetReader;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Lecturas consistentes por clave: activos, eventos procesados y claves de idempotencia. */
public final class DynamoInventoryReader implements VehicleAssetReader, ProcessedEvents, IdempotencyStore {
    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoInventoryReader(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public Optional<VehicleAsset> findById(VehicleId id) {
        return get(InventoryTable.vehicleKey(id.value())).map(AssetItems::toAsset);
    }

    @Override
    public boolean isProcessed(UUID eventId) {
        return get(InventoryTable.processedKey(eventId.toString())).isPresent();
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return get(InventoryTable.idempotencyKey(key)).map(item -> new IdempotencyRecord(key,
                item.get("operation").s(), new VehicleId(item.get("vehicleId").s())));
    }

    private Optional<Map<String, AttributeValue>> get(String key) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(key)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }
}
