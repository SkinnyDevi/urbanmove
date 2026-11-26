package com.urbanmove.availability.application;

import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.List;
import java.util.Objects;

/** Hechos de otros servicios que cambian la disponibilidad de un vehículo. */
public sealed interface VehicleEvent {
    record Purchased(VehicleId vehicleId, VehicleType vehicleType, ZoneId initialZone) implements VehicleEvent {
        public Purchased {
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(vehicleType);
            Objects.requireNonNull(initialZone);
        }
    }

    record Reserved(VehicleId vehicleId) implements VehicleEvent {
    }

    record ReservationReleased(VehicleId vehicleId) implements VehicleEvent {
    }

    record RideStarted(VehicleId vehicleId) implements VehicleEvent {
    }

    record RideFinished(VehicleId vehicleId, ZoneId destination) implements VehicleEvent {
    }

    record BatteryLow(VehicleId vehicleId) implements VehicleEvent {
    }

    record WithdrawnForMaintenance(VehicleId vehicleId) implements VehicleEvent {
    }

    record ReturnedToService(VehicleId vehicleId, ZoneId zone) implements VehicleEvent {
    }

    record Retired(VehicleId vehicleId) implements VehicleEvent {
    }

    record Relocated(List<VehicleId> vehicleIds, ZoneId targetZone) implements VehicleEvent {
        public Relocated {
            vehicleIds = List.copyOf(vehicleIds);
            Objects.requireNonNull(targetZone);
        }
    }
}
