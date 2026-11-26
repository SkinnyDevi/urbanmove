package com.urbanmove.inventory.infrastructure.dynamo;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Utilidades para construir elementos y transacciones de DynamoDB. */
final class DynamoItems {
    static final String NOT_EXISTS = "attribute_not_exists(" + InventoryTable.PK + ")";
    static final String SAME_VERSION = "rowVersion = :expected";

    private DynamoItems() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static TransactWriteItem put(String table, Map<String, AttributeValue> item, String condition,
                                 Map<String, AttributeValue> values) {
        return TransactWriteItem.builder().put(p -> {
            p.tableName(table).item(item);
            if (condition != null) {
                p.conditionExpression(condition);
            }
            if (values != null) {
                p.expressionAttributeValues(values);
            }
        }).build();
    }

    static TransactWriteItem putVersioned(String table, Map<String, AttributeValue> item, long expectedVersion) {
        return put(table, item, SAME_VERSION, Map.of(":expected", n(expectedVersion)));
    }

    static boolean failed(TransactionCanceledException e, int index) {
        List<CancellationReason> reasons = e.cancellationReasons();
        return reasons != null && reasons.size() > index && "ConditionalCheckFailed".equals(reasons.get(index).code());
    }
}
