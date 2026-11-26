package com.urbanmove.inventory.infrastructure.dynamo;

import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.n;
import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.s;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_PK;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_SK;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.PK;

import com.urbanmove.inventory.domain.AdminStatus;
import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.ServiceStatus;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Conversión entre un activo y su elemento de DynamoDB. */
final class AssetItems {

    private AssetItems() {
    }

    static String sortKey(VehicleType type, VehicleId id) {
        return type.name() + "#" + id.value();
    }

    static Map<String, AttributeValue> toItem(VehicleAsset asset, long version) {
        VehicleAsset.Snapshot a = asset.snapshot();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(InventoryTable.vehicleKey(a.vehicleId().value())));
        item.put(GSI1_PK, s(InventoryTable.adminStatusKey(AdminStatus.of(a.status()).name())));
        item.put(GSI1_SK, s(sortKey(a.vehicleType(), a.vehicleId())));
        item.put("vehicleId", s(a.vehicleId().value()));
        item.put("vehicleType", s(a.vehicleType().name()));
        item.put("model", s(a.model()));
        item.put("purchaseDate", s(a.purchaseDate().toString()));
        item.put("status", s(a.status().name()));
        item.put("registeredAt", s(a.registeredAt().toString()));
        item.put("rowVersion", n(version));
        if (a.purchasePrice() != null) {
            item.put("priceAmount", AttributeValue.fromN(a.purchasePrice().amount().toPlainString()));
        }
        putIfPresent(item, "retiredAt", a.retiredAt(), Instant::toString);
        putIfPresent(item, "retirementReason", a.retirementReason(), Function.identity());
        return item;
    }

    static VehicleAsset toAsset(Map<String, AttributeValue> item) {
        AttributeValue price = item.get("priceAmount");
        return VehicleAsset.rehydrate(new VehicleAsset.Snapshot(
                new VehicleId(item.get("vehicleId").s()),
                VehicleType.valueOf(item.get("vehicleType").s()),
                item.get("model").s(),
                LocalDate.parse(item.get("purchaseDate").s()),
                price == null ? null : new Money(new BigDecimal(price.n()), Money.EUR),
                ServiceStatus.valueOf(item.get("status").s()),
                Instant.parse(item.get("registeredAt").s()),
                optional(item, "retiredAt", Instant::parse),
                optional(item, "retirementReason", Function.identity()),
                Long.parseLong(item.get("rowVersion").n())));
    }

    private static <T> void putIfPresent(Map<String, AttributeValue> item, String name, T value,
                                         Function<T, String> toText) {
        if (value != null) {
            item.put(name, s(toText.apply(value)));
        }
    }

    private static <T> T optional(Map<String, AttributeValue> item, String name, Function<String, T> parser) {
        AttributeValue value = item.get(name);
        return value == null ? null : parser.apply(value.s());
    }
}
