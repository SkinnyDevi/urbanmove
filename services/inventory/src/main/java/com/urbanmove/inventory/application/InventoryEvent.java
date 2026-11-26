package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import java.time.LocalDate;
import java.util.Objects;

/** Hechos de otros servicios que cambian el registro de activos. */
public sealed interface InventoryEvent {
    VehicleId vehicleId();

    record Purchased(VehicleId vehicleId, VehicleType vehicleType, String model, LocalDate purchaseDate,
                     Money purchasePrice) implements InventoryEvent {
        public Purchased {
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(vehicleType);
            Objects.requireNonNull(model);
            Objects.requireNonNull(purchaseDate);
        }
    }

    record Withdrawn(VehicleId vehicleId) implements InventoryEvent {
    }

    record ReturnedToService(VehicleId vehicleId) implements InventoryEvent {
    }
}
