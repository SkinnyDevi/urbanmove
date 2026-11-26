package com.urbanmove.inventory.infrastructure.health;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/** La tabla de DynamoDB existe y está ACTIVE. */
public final class DynamoDbHealthCheck implements DependencyHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(DynamoDbHealthCheck.class);

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbHealthCheck(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public String name() {
        return "dynamodb";
    }

    @Override
    public boolean isUp() {
        try {
            return dynamo.describeTable(r -> r.tableName(table)).table().tableStatus() == TableStatus.ACTIVE;
        } catch (SdkException e) {
            log.warn("DynamoDB health check failed: {}", e.getMessage());
            return false;
        }
    }
}
