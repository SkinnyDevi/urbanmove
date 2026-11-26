package com.urbanmove.inventory.interfaces.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.urbanmove.inventory.domain.AdminStatus;
import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.VehicleAsset;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** DTO de salida: esquema Vehicle de docs/contracts/openapi/inventory.yaml. */
public record VehicleResponse(String vehicleId, String vehicleType, String model, String purchaseDate,
                              @JsonInclude(JsonInclude.Include.NON_NULL) Price purchasePrice, String adminStatus,
                              String serviceStatus, String registeredAt, String retiredAt, String retirementReason) {

    public record Price(BigDecimal amount, String currency) {
        static Price from(Money money) {
            return money == null ? null : new Price(money.amount(), money.currency());
        }
    }

    public record Page(List<VehicleResponse> items, String nextCursor) {
    }

    public static VehicleResponse from(VehicleAsset asset) {
        VehicleAsset.Snapshot s = asset.snapshot();
        return new VehicleResponse(
                s.vehicleId().value(),
                s.vehicleType().name(),
                s.model(),
                s.purchaseDate().toString(),
                Price.from(s.purchasePrice()),
                AdminStatus.of(s.status()).name(),
                s.status().name(),
                s.registeredAt().toString(),
                text(s.retiredAt()),
                s.retirementReason());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
