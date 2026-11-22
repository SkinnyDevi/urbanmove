package com.urbanmove.synthgen;

/** Cómo se comportan usuarios y vehículos: probabilidades, umbrales y tiempos. */
public final class Rules {
    public final double scooterShare = 0.55;

    public final double acceptOtherType = 0.5;

    public final double reserveFirst = 0.7;

    public final int reservationTtlSeconds = 15 * 60;

    public final double afterReservationRide = 0.80;

    public final double afterReservationCancel = 0.12;

    public final double bikeSpeedKmh = 13.0;
    public final double scooterSpeedKmh = 15.0;

    public final double sameZoneTrip = 0.15;

    public final boolean balanceDailyFlows = true;
    public final double bikeUnlockFee = 0.50;
    public final double scooterUnlockFee = 1.00;
    public final double bikePerMinute = 0.10;
    public final double scooterPerMinute = 0.17;

    public final double bikeDrainPerKm = 1.2;
    public final double scooterDrainPerKm = 2.5;

    public final int batteryLowThreshold = 15;

    public final double breakdownProbability = 0.004;

    public final int inspectionAfterUsages = 30;

    public final int technicianOpenHour = 7;
    public final int technicianCloseHour = 22;

    public final int hotspotMaxAvailable = 3;

    public final int hotspotMinSignals = 10;

    public double speedKmh(VehicleType type) {
        return type == VehicleType.BIKE ? bikeSpeedKmh : scooterSpeedKmh;
    }

    public double unlockFee(VehicleType type) {
        return type == VehicleType.BIKE ? bikeUnlockFee : scooterUnlockFee;
    }

    public double perMinute(VehicleType type) {
        return type == VehicleType.BIKE ? bikePerMinute : scooterPerMinute;
    }

    public double drainPerKm(VehicleType type) {
        return type == VehicleType.BIKE ? bikeDrainPerKm : scooterDrainPerKm;
    }
}
