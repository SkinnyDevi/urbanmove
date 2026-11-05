package com.urbanmove.synthgen;

/** Tipos de vehículo, con los mismos nombres que usan los contratos. */
public enum VehicleType {
    BIKE("UrbanMove E-Bike City", 1150.0),
    SCOOTER("UrbanMove Scooter S2", 620.0);

    public final String model;
    public final double price;

    VehicleType(String model, double price) {
        this.model = model;
        this.price = price;
    }

    public VehicleType other() {
        return this == BIKE ? SCOOTER : BIKE;
    }
}
