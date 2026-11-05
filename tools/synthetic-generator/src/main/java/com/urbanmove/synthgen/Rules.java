package com.urbanmove.synthgen;

/**
 * Cómo se comportan usuarios y vehículos: probabilidades, umbrales y tiempos.
 * Para cambiar el comportamiento de la simulación, se cambia aquí.
 */
public final class Rules {

    // ---- Tipo de vehículo que pide la gente
    /** El 55 % quiere patinete y el resto bici. */
    public final double scooterShare = 0.55;
    /** Si no hay de su tipo, la mitad acepta el otro. */
    public final double acceptOtherType = 0.5;

    // ---- Reservas
    /** El 70 % reserva antes de coger el vehículo. */
    public final double reserveFirst = 0.7;
    /** Una reserva caduca a los 15 minutos. */
    public final int reservationTtlSeconds = 15 * 60;
    /** De las reservas: el 80 % acaban en viaje... */
    public final double afterReservationRide = 0.80;
    /** ...el 12 % se cancelan y el resto caduca. */
    public final double afterReservationCancel = 0.12;

    // ---- Viajes
    public final double bikeSpeedKmh = 13.0;
    public final double scooterSpeedKmh = 15.0;
    /** Peso relativo de acabar en la misma zona donde empezó el viaje. */
    public final double sameZoneTrip = 0.15;
    /** En un día laborable, a cada zona llegan tantos vehículos como salen (evita que la flota "derive"). */
    public final boolean balanceDailyFlows = true;
    public final double bikeUnlockFee = 0.50;
    public final double scooterUnlockFee = 1.00;
    public final double bikePerMinute = 0.10;
    public final double scooterPerMinute = 0.17;

    // ---- Batería y mantenimiento
    /** Batería (%) que gasta cada km. */
    public final double bikeDrainPerKm = 1.2;
    public final double scooterDrainPerKm = 2.5;
    /** Por debajo de este % se publica VehicleBatteryLow. */
    public final int batteryLowThreshold = 15;
    /** Probabilidad de avería tras un viaje (1 de cada 250). */
    public final double breakdownProbability = 0.004;
    /** Revisión obligatoria cada 30 usos. */
    public final int inspectionAfterUsages = 30;
    /** Los técnicos trabajan de 7:00 a 21:59. */
    public final int technicianOpenHour = 7;
    public final int technicianCloseHour = 22;

    // ---- Regla de déficit del enunciado (sección 8.3), calculada como "verdad" para comprobar la Lambda
    /** Menos de 3 vehículos disponibles... */
    public final int hotspotMaxAvailable = 3;
    /** ...y más de 10 reservas o viajes iniciados en la última hora. */
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
