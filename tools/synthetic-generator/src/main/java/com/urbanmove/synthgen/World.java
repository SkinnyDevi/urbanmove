package com.urbanmove.synthgen;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** El "mundo" de la simulación: crear la flota y los usuarios, y medir distancias. */
public final class World {

    private static final List<String> FIRST_NAMES = List.of("Laura", "Carlos", "Nayra", "Airam", "Lucía", "Jonay",
            "Marta", "Yeray", "Elena", "Acoidan", "Sara", "Daniel", "Cristina", "Tamara", "Pablo", "Iballa", "Javier",
            "Ana", "Héctor", "Irene", "Raúl", "Beatriz", "Adrián", "Carmen", "Óscar", "Noelia", "Iván", "Paula",
            "Rubén", "Yaiza");
    private static final List<String> LAST_NAMES = List.of("Santana", "Rodríguez", "Hernández", "Suárez", "Medina",
            "Quintana", "González", "Pérez", "Ramírez", "Déniz", "Cabrera", "Ortega", "Falcón", "Alemán", "Navarro",
            "Betancor", "Trujillo", "Monzón", "Sosa", "Marrero");

    private World() {
    }

    /** Reparte la flota entre zonas en proporción a su capacidad, con batería entre el 60 % y el 100 %. */
    public static List<Vehicle> buildFleet(List<Zone> zones, int count, double scooterShare, Dice dice,
                                           IdFactory ids) {
        double[] weights = zones.stream().mapToDouble(Zone::capacity).toArray();
        List<Vehicle> fleet = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            VehicleType type = dice.chance(scooterShare) ? VehicleType.SCOOTER : VehicleType.BIKE;
            Zone zone = dice.weighted(zones, weights);
            fleet.add(new Vehicle(ids.sequential("veh", 4), type, zone.zoneId(), dice.between(60, 100)));
        }
        return fleet;
    }

    public static User makeUser(IdFactory ids, Dice dice, String role) {
        String userId = "maintenance".equals(role) ? ids.sequential("usr-tech", 2) : ids.sequential("usr", 4);
        String first = dice.pick(FIRST_NAMES);
        String last = dice.pick(LAST_NAMES);
        String number = userId.substring(userId.lastIndexOf('-') + 1);
        String email = ascii(first) + "." + ascii(last) + "." + number + "@example.com";
        return new User(userId, email, first + " " + last, role);
    }

    /** Distancia en línea recta entre dos puntos del globo, en km (fórmula del haversine). */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** Un punto al azar dentro del radio de la zona: {lat, lon} con 6 decimales. */
    public static double[] randomPoint(Zone zone, Dice dice) {
        double distance = zone.radiusMeters() * Math.sqrt(dice.nextDouble());
        double angle = dice.uniform(0, 2 * Math.PI);
        double dLat = distance * Math.cos(angle) / 111_320;
        double dLon = distance * Math.sin(angle) / (111_320 * Math.cos(Math.toRadians(zone.lat())));
        return new double[] {round(zone.lat() + dLat, 6), round(zone.lon() + dLon, 6)};
    }

    public static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** "Lucía" -> "lucia", para construir emails sin tildes. */
    private static String ascii(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
