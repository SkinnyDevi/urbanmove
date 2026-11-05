package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Construye los eventos con el formato exacto de docs/contracts/asyncapi/: el sobre (eventId, timestamp,
 * source...) y la carta (payload). Un método por tipo de evento.
 *
 * <p>Los tiempos se reciben en segundos desde el inicio de la simulación y se convierten aquí a fechas.
 */
public final class EventFactory {

    public static final String SCHEMA_VERSION = "1.0.0";

    /** Productor oficial de cada evento (debe coincidir con los contratos). */
    public static final Map<String, String> PRODUCERS = Map.of(
            "UserRegistered", "identity",
            "VehiclePurchased", "acquisitions",
            "VehicleReserved", "reservations",
            "ReservationCancelled", "reservations",
            "ReservationExpired", "reservations",
            "RideStarted", "rides",
            "RideFinished", "rides",
            "VehicleBatteryLow", "telemetry",
            "VehicleWithdrawnForMaintenance", "maintenance",
            "VehicleReturnedToService", "maintenance");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final Instant start;
    private final IdFactory ids;

    public EventFactory(Instant start, IdFactory ids) {
        this.start = start;
        this.ids = ids;
    }

    /** Segundos desde el inicio -> "2026-11-09T07:15:00.000Z". */
    public String ts(long t) {
        return TIMESTAMP.format(start.plusSeconds(t));
    }

    private ObjectNode event(long t, String eventType, String correlationId, ObjectNode payload) {
        ObjectNode event = JSON.objectNode();
        event.put("eventId", ids.uuid4());
        event.put("timestamp", ts(t));
        event.put("source", "urbanmove." + PRODUCERS.get(eventType));
        event.put("eventType", eventType);
        event.put("schemaVersion", SCHEMA_VERSION);
        event.put("correlationId", correlationId);
        event.set("payload", payload);
        return event;
    }

    private static ObjectNode money(double amount) {
        return JSON.objectNode().put("amount", amount).put("currency", "EUR");
    }

    // ------------------------------------------------------------------ usuarios y flota

    public ObjectNode userRegistered(long t, String corr, User user) {
        return event(t, "UserRegistered", corr, JSON.objectNode()
                .put("userId", user.userId).put("email", user.email)
                .put("fullName", user.fullName).put("role", user.role));
    }

    public ObjectNode vehiclePurchased(long t, String corr, Vehicle vehicle) {
        ObjectNode payload = JSON.objectNode()
                .put("vehicleId", vehicle.vehicleId).put("vehicleType", vehicle.type.name())
                .put("model", vehicle.type.model).put("purchaseDate", ts(t).substring(0, 10));
        payload.set("purchasePrice", money(vehicle.type.price));
        payload.put("initialZoneId", vehicle.zoneId);
        return event(t, "VehiclePurchased", corr, payload);
    }

    // ------------------------------------------------------------------ reservas

    private ObjectNode reservationBase(String reservationId, Vehicle vehicle, User user) {
        return JSON.objectNode()
                .put("reservationId", reservationId).put("vehicleId", vehicle.vehicleId)
                .put("vehicleType", vehicle.type.name()).put("userId", user.userId).put("zoneId", vehicle.zoneId);
    }

    public ObjectNode vehicleReserved(long t, String corr, String reservationId, Vehicle vehicle, User user,
                                      long expiresAt) {
        return event(t, "VehicleReserved", corr,
                reservationBase(reservationId, vehicle, user).put("expiresAt", ts(expiresAt)));
    }

    public ObjectNode reservationCancelled(long t, String corr, String reservationId, Vehicle vehicle, User user,
                                           String reason) {
        ObjectNode payload = reservationBase(reservationId, vehicle, user);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return event(t, "ReservationCancelled", corr, payload);
    }

    public ObjectNode reservationExpired(long t, String corr, String reservationId, Vehicle vehicle, User user,
                                         long reservedAt) {
        return event(t, "ReservationExpired", corr, reservationBase(reservationId, vehicle, user)
                .put("reservedAt", ts(reservedAt)).put("expiredAt", ts(t)));
    }

    // ------------------------------------------------------------------ viajes

    public ObjectNode rideStarted(long t, String corr, String rideId, Vehicle vehicle, User user,
                                  String reservationId) {
        return event(t, "RideStarted", corr, JSON.objectNode()
                .put("rideId", rideId).put("vehicleId", vehicle.vehicleId)
                .put("vehicleType", vehicle.type.name()).put("userId", user.userId)
                .put("reservationId", reservationId)   // null si se cogió sin reservar
                .put("originZoneId", vehicle.zoneId).put("startedAt", ts(t)));
    }

    public ObjectNode rideFinished(long t, String corr, Ride ride, Vehicle vehicle, User user, double cost) {
        ObjectNode payload = JSON.objectNode()
                .put("rideId", ride.rideId()).put("vehicleId", vehicle.vehicleId)
                .put("vehicleType", vehicle.type.name()).put("userId", user.userId)
                .put("originZoneId", ride.originZoneId()).put("destinationZoneId", ride.destinationZoneId())
                .put("startedAt", ts(ride.startedAt())).put("finishedAt", ts(t))
                .put("durationSeconds", t - ride.startedAt()).put("distanceKm", ride.distanceKm());
        payload.set("preliminaryCost", money(cost));
        return event(t, "RideFinished", corr, payload);
    }

    // ------------------------------------------------------------------ telemetría y mantenimiento

    public ObjectNode batteryLow(long t, String corr, Vehicle vehicle, int threshold, double[] location) {
        ObjectNode payload = JSON.objectNode()
                .put("vehicleId", vehicle.vehicleId).put("vehicleType", vehicle.type.name())
                .put("batteryLevel", (int) vehicle.battery).put("threshold", threshold)
                .put("zoneId", vehicle.zoneId);
        payload.set("location", JSON.objectNode().put("lat", location[0]).put("lon", location[1]));
        return event(t, "VehicleBatteryLow", corr, payload);
    }

    public ObjectNode withdrawn(long t, String corr, Vehicle vehicle, String orderId, String reason,
                                String technicianId) {
        return event(t, "VehicleWithdrawnForMaintenance", corr, JSON.objectNode()
                .put("vehicleId", vehicle.vehicleId).put("vehicleType", vehicle.type.name())
                .put("maintenanceOrderId", orderId).put("reason", reason).put("zoneId", vehicle.zoneId)
                .put("technicianId", technicianId));
    }

    public ObjectNode returned(long t, String corr, Vehicle vehicle, String orderId, List<String> work,
                               long outOfServiceSeconds) {
        ObjectNode payload = JSON.objectNode()
                .put("vehicleId", vehicle.vehicleId).put("vehicleType", vehicle.type.name())
                .put("maintenanceOrderId", orderId);
        ArrayNode workPerformed = payload.putArray("workPerformed");
        work.forEach(workPerformed::add);
        payload.put("zoneId", vehicle.zoneId).put("outOfServiceSeconds", outOfServiceSeconds);
        return event(t, "VehicleReturnedToService", corr, payload);
    }
}
