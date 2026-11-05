package com.urbanmove.synthgen;

/** Un vehículo durante la simulación. Su estado cambia según lo que le va pasando. */
public final class Vehicle {

    public final String vehicleId;
    public final VehicleType type;
    String zoneId;
    double battery;
    String state = "AVAILABLE";
    int usagesSinceService;
    /** true mientras espera a que un técnico lo retire: no se puede alquilar. */
    boolean pendingMaintenance;
    Long withdrawnAt;

    Vehicle(String vehicleId, VehicleType type, String zoneId, double battery) {
        this.vehicleId = vehicleId;
        this.type = type;
        this.zoneId = zoneId;
        this.battery = battery;
    }

    boolean isRentable() {
        return "AVAILABLE".equals(state) && !pendingMaintenance;
    }

    public String zoneId() {
        return zoneId;
    }
}
