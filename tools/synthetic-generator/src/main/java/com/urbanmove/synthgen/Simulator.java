package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.contracts.ContractValidator;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.LongConsumer;

/**
 * El motor de la simulación.
 *
 * <p>Funciona con una "agenda" ordenada por tiempo. Cada entrada es "en el segundo T, haz esto". El motor
 * saca siempre la entrada más próxima, la ejecuta y, si esa acción provoca otras en el futuro (empezar un
 * viaje provoca terminarlo 20 minutos después), las apunta en la agenda. Así se simulan días enteros en
 * un segundo, sin esperar en tiempo real.
 *
 * <p>El tiempo se mide en segundos desde el inicio (el primer día a las 00:00). Los tiempos negativos
 * son el día anterior, cuando se dan de alta la flota y los usuarios.
 */
public final class Simulator {

    private static final int HOUR = 3_600;
    private static final int DAY = 24 * HOUR;
    private static final int HOURS_PER_DAY = ConfigLoader.HOURS_PER_DAY;
    private static final List<String> CANCEL_REASONS =
            Arrays.asList(null, null, "He cambiado de planes", "Ya no lo necesito", "Me he equivocado de vehículo");
    private static final Map<String, int[]> WORK_DURATION_SECONDS = Map.of(
            "RECHARGE", new int[] {2 * HOUR, 4 * HOUR},
            "REPAIR", new int[] {6 * HOUR, 24 * HOUR},
            "INSPECTION", new int[] {HOUR, 2 * HOUR});

    /** Una entrada de la agenda. A igual tiempo, va primero la que se apuntó antes. */
    private record Scheduled(long t, long order, LongConsumer action) implements Comparable<Scheduled> {
        @Override
        public int compareTo(Scheduled other) {
            int byTime = Long.compare(t, other.t);
            return byTime != 0 ? byTime : Long.compare(order, other.order);
        }
    }

    private final Settings settings;
    private final Rules rules;
    private final Dice dice;
    private final IdFactory ids;
    private final EventFactory factory;
    private final List<Zone> zones;
    private final Map<String, Zone> zoneById = new LinkedHashMap<>();
    private final Map<String, DemandProfile> profiles;
    private final ContractValidator validator;
    private final Map<String, Double> arrivalBalance;

    private final PriorityQueue<Scheduled> agenda = new PriorityQueue<>();
    private long nextOrder;
    private final List<ObjectNode> events = new ArrayList<>();
    private final List<Vehicle> vehicles = new ArrayList<>();
    private final List<User> users = new ArrayList<>();
    private final List<User> technicians = new ArrayList<>();
    private final Map<HourZone, Integer> signals = new HashMap<>();
    private final Map<HourZone, Integer> unmet = new HashMap<>();
    private final List<ZoneHour> zoneHours = new ArrayList<>();

    public Simulator(Settings settings, Rules rules) {
        this.settings = settings;
        this.rules = rules;
        Random rng = new Random(settings.seed());   // todo lo aleatorio sale de aquí: misma semilla, mismos datos
        this.dice = new Dice(rng);
        this.ids = new IdFactory(rng);
        Instant start = settings.startDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        this.factory = new EventFactory(start, ids);
        this.zones = ConfigLoader.loadZones(settings.zonesFile());
        zones.forEach(zone -> zoneById.put(zone.zoneId(), zone));
        this.profiles = ConfigLoader.loadProfiles(settings.profilesFile(), zones);
        this.validator = settings.validate() ? ContractValidator.fromDefaultLocation() : null;
        this.arrivalBalance = rules.balanceDailyFlows ? balanceArrivals() : neutralBalance();
    }

    public static GenerationResult generate(Settings settings) {
        return new Simulator(settings, new Rules()).run();
    }

    public GenerationResult run() {
        setupWorld();
        scheduleHourChecks();   // se apuntan antes que la demanda: a igual segundo, la foto va primero
        scheduleDemand();
        while (!agenda.isEmpty()) {
            Scheduled next = agenda.poll();
            next.action().accept(next.t());
        }
        List<User> everyone = new ArrayList<>(technicians);
        everyone.addAll(users);
        return new GenerationResult(settings, rules, zones, List.copyOf(vehicles), List.copyOf(everyone),
                List.copyOf(events), List.copyOf(zoneHours), Map.copyOf(unmet));
    }

    // ================================================================ agenda

    private void schedule(long t, LongConsumer action) {
        agenda.add(new Scheduled(t, nextOrder++, action));
    }

    private void emit(ObjectNode event) {
        if (validator != null) {
            List<String> errors = validator.validate(event);
            if (!errors.isEmpty()) {
                throw new InvalidGeneratedEventException(event.get("eventType").asText() + " inválido: " + errors);
            }
        }
        events.add(event);
    }

    // ================================================================ preparación

    /** El día anterior al inicio se dan de alta la flota, los técnicos y los usuarios iniciales. */
    private void setupWorld() {
        for (Vehicle vehicle : World.buildFleet(zones, settings.vehicles(), rules.scooterShare, dice, ids)) {
            schedule(-DAY + 8L * HOUR + dice.below(4 * HOUR), t -> onVehiclePurchased(t, vehicle));
        }
        for (int i = 0; i < settings.technicians(); i++) {
            User technician = World.makeUser(ids, dice, "maintenance");
            schedule(-DAY + 7L * HOUR + dice.below(HOUR), t -> onUserRegistered(t, technician));
        }
        for (int i = 0; i < settings.users(); i++) {
            long when = -DAY + dice.below(DAY);
            User user = World.makeUser(ids, dice, "user");
            schedule(when, t -> onUserRegistered(t, user));
        }
        for (int day = 0; day < settings.days(); day++) {
            int newUsers = dice.poisson(settings.newUsersPerDay());
            for (int i = 0; i < newUsers; i++) {
                long when = (long) day * DAY + dice.below(DAY);
                User user = World.makeUser(ids, dice, "user");
                schedule(when, t -> onUserRegistered(t, user));
            }
        }
    }

    private void scheduleHourChecks() {
        for (long hour = 0; hour < (long) settings.days() * HOURS_PER_DAY; hour++) {
            long hourIndex = hour;
            schedule((hour + 1) * HOUR, t -> onHourEnd(hourIndex));
        }
    }

    private double dayFactor(Zone zone, long dayIndex) {
        int weekday = Math.floorMod(settings.startDate().getDayOfWeek().getValue() - 1 + dayIndex, 7);
        return weekday >= 5 ? profiles.get(zone.profile()).weekendFactor() : 1.0;   // 5 = sábado, 6 = domingo
    }

    /** Decide cuántas personas querrán un vehículo en cada zona y en cada hora. */
    private void scheduleDemand() {
        double weekdayTotal = 0;
        for (Zone zone : zones) {
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                weekdayTotal += profiles.get(zone.profile()).departures()[h] * zone.popularity();
            }
        }
        for (int day = 0; day < settings.days(); day++) {
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                long hourStart = (long) day * DAY + (long) hour * HOUR;
                for (Zone zone : zones) {
                    double weight = profiles.get(zone.profile()).departures()[hour] * zone.popularity();
                    double expected = settings.tripsPerDay() * weight * dayFactor(zone, day) / weekdayTotal;
                    int requests = dice.poisson(expected);
                    for (int i = 0; i < requests; i++) {
                        schedule(hourStart + dice.below(HOUR), t -> onRequest(t, zone));
                    }
                }
            }
        }
    }

    /**
     * Calcula un multiplicador por zona para que, en un día laborable, lleguen tantos vehículos como salen.
     * Sin esto la flota "deriva": cada día se acumulan más vehículos en unas zonas y faltan en otras.
     * Los desequilibrios por horas, que son los que interesan, se mantienen.
     */
    private Map<String, Double> balanceArrivals() {
        Map<String, Double> factor = neutralBalance();
        for (int round = 0; round < 60; round++) {
            Map<String, Double> inflow = new LinkedHashMap<>();
            zones.forEach(zone -> inflow.put(zone.zoneId(), 0.0));
            for (int h = 0; h < HOURS_PER_DAY; h++) {
                for (Zone origin : zones) {
                    double leaving = profiles.get(origin.profile()).departures()[h] * origin.popularity();
                    double[] weights = new double[zones.size()];
                    double total = 0;
                    for (int i = 0; i < zones.size(); i++) {
                        Zone z = zones.get(i);
                        weights[i] = profiles.get(z.profile()).arrivals()[h] * z.popularity() * factor.get(z.zoneId())
                                * (z == origin ? rules.sameZoneTrip : 1.0);
                        total += weights[i];
                    }
                    for (int i = 0; i < zones.size(); i++) {
                        inflow.merge(zones.get(i).zoneId(), leaving * weights[i] / total, Double::sum);
                    }
                }
            }
            for (Zone zone : zones) {
                double outflow = 0;
                for (int h = 0; h < HOURS_PER_DAY; h++) {
                    outflow += profiles.get(zone.profile()).departures()[h] * zone.popularity();
                }
                factor.put(zone.zoneId(), factor.get(zone.zoneId()) * Math.sqrt(outflow / inflow.get(zone.zoneId())));
            }
        }
        return factor;
    }

    private Map<String, Double> neutralBalance() {
        Map<String, Double> neutral = new LinkedHashMap<>();
        zones.forEach(zone -> neutral.put(zone.zoneId(), 1.0));
        return neutral;
    }

    // ================================================================ altas

    private void onUserRegistered(long t, User user) {
        ("maintenance".equals(user.role) ? technicians : users).add(user);
        emit(factory.userRegistered(t, ids.uuid4(), user));
    }

    private void onVehiclePurchased(long t, Vehicle vehicle) {
        vehicles.add(vehicle);
        emit(factory.vehiclePurchased(t, ids.uuid4(), vehicle));
    }

    // ================================================================ usuarios

    private Vehicle pickVehicle(String zoneId, VehicleType type) {
        List<Vehicle> candidates = vehicles.stream()
                .filter(v -> v.zoneId.equals(zoneId) && v.type == type && v.isRentable())
                .toList();
        return candidates.isEmpty() ? null : dice.pick(candidates);
    }

    /** Una persona en esta zona quiere un vehículo. */
    private void onRequest(long t, Zone zone) {
        HourZone slot = new HourZone(Math.floorDiv(t, HOUR), zone.zoneId());
        VehicleType wanted = dice.chance(rules.scooterShare) ? VehicleType.SCOOTER : VehicleType.BIKE;
        Vehicle found = pickVehicle(zone.zoneId(), wanted);
        if (found == null && dice.chance(rules.acceptOtherType)) {
            found = pickVehicle(zone.zoneId(), wanted.other());
        }
        if (found == null) {
            unmet.merge(slot, 1, Integer::sum);   // demanda no atendida: no hay evento para esto
            return;
        }
        List<User> freeUsers = users.stream().filter(u -> !u.busy).toList();
        if (freeUsers.isEmpty()) {
            return;
        }
        Vehicle vehicle = found;
        User user = dice.pick(freeUsers);
        user.busy = true;
        String corr = ids.uuid4();   // un correlationId para todo el flujo de esta persona
        signals.merge(slot, 1, Integer::sum);

        if (!dice.chance(rules.reserveFirst)) {
            startRide(t, vehicle, user, null, corr);
            return;
        }
        String reservationId = ids.sequential("res", 6);
        vehicle.state = "RESERVED";
        emit(factory.vehicleReserved(t, corr, reservationId, vehicle, user, t + rules.reservationTtlSeconds));
        double roll = dice.nextDouble();
        if (roll < rules.afterReservationRide) {
            schedule(t + dice.between(60, 600), s -> startRide(s, vehicle, user, reservationId, corr));
        } else if (roll < rules.afterReservationRide + rules.afterReservationCancel) {
            schedule(t + dice.between(60, 600), s -> onReservationCancelled(s, vehicle, user, reservationId, corr));
        } else {
            schedule(t + rules.reservationTtlSeconds, s -> onReservationExpired(s, vehicle, user, reservationId, corr, t));
        }
    }

    private void onReservationCancelled(long t, Vehicle vehicle, User user, String reservationId, String corr) {
        vehicle.state = "AVAILABLE";
        user.busy = false;
        emit(factory.reservationCancelled(t, corr, reservationId, vehicle, user, dice.pick(CANCEL_REASONS)));
    }

    private void onReservationExpired(long t, Vehicle vehicle, User user, String reservationId, String corr,
                                      long reservedAt) {
        vehicle.state = "AVAILABLE";
        user.busy = false;
        emit(factory.reservationExpired(t, corr, reservationId, vehicle, user, reservedAt));
    }

    // ================================================================ viajes

    private Zone pickDestination(Zone origin, long t) {
        int hour = Math.floorMod(Math.floorDiv(t, HOUR), HOURS_PER_DAY);
        long day = Math.floorDiv(t, DAY);
        double[] weights = new double[zones.size()];
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            weights[i] = profiles.get(z.profile()).arrivals()[hour] * z.popularity() * dayFactor(z, day)
                    * arrivalBalance.get(z.zoneId()) * (z == origin ? rules.sameZoneTrip : 1.0);
        }
        return dice.weighted(zones, weights);
    }

    private void startRide(long t, Vehicle vehicle, User user, String reservationId, String corr) {
        Zone origin = zoneById.get(vehicle.zoneId);
        Zone destination = pickDestination(origin, t);
        double km = destination == origin
                ? dice.uniform(0.4, 1.8)
                // la línea recta por 1,3: las calles nunca van rectas
                : World.haversineKm(origin.lat(), origin.lon(), destination.lat(), destination.lon()) * 1.3
                  * dice.uniform(0.85, 1.2);
        double distance = World.round(km, 2);
        double minutes = distance / rules.speedKmh(vehicle.type) * 60 * dice.uniform(0.9, 1.4) + dice.uniform(1, 3);
        long duration = Math.max(120, (long) (minutes * 60));

        String rideId = ids.sequential("ride", 6);
        vehicle.state = "IN_USE";
        emit(factory.rideStarted(t, corr, rideId, vehicle, user, reservationId));
        Ride ride = new Ride(rideId, origin.zoneId(), destination.zoneId(), t, distance);
        schedule(t + duration, s -> onRideFinished(s, vehicle, user, ride, corr));
    }

    private void onRideFinished(long t, Vehicle vehicle, User user, Ride ride, String corr) {
        VehicleType type = vehicle.type;
        vehicle.zoneId = ride.destinationZoneId();
        vehicle.battery = Math.max(1.0,
                vehicle.battery - ride.distanceKm() * rules.drainPerKm(type) * dice.uniform(0.8, 1.2));
        vehicle.usagesSinceService++;
        vehicle.state = "AVAILABLE";
        long minutes = (long) Math.ceil((t - ride.startedAt()) / 60.0);
        double cost = World.round(rules.unlockFee(type) + rules.perMinute(type) * minutes, 2);
        emit(factory.rideFinished(t, corr, ride, vehicle, user, cost));
        user.busy = false;
        afterRideChecks(t, vehicle);
    }

    // ================================================================ mantenimiento

    /** Cuándo actúa un técnico: dentro de su horario, tras un retraso; si no, al abrir al día siguiente. */
    private long workingTime(long t, int minDelay, int maxDelay) {
        int hour = Math.floorMod(Math.floorDiv(t, HOUR), HOURS_PER_DAY);
        if (hour >= rules.technicianOpenHour && hour < rules.technicianCloseHour) {
            return t + dice.between(minDelay, maxDelay);
        }
        long day = Math.floorDiv(t, DAY);
        long opening = hour < rules.technicianOpenHour
                ? day * DAY + (long) rules.technicianOpenHour * HOUR
                : (day + 1) * DAY + (long) rules.technicianOpenHour * HOUR;
        return opening + dice.between(0, 90 * 60);
    }

    private void afterRideChecks(long t, Vehicle vehicle) {
        if (dice.chance(rules.breakdownProbability)) {
            vehicle.pendingMaintenance = true;
            long when = workingTime(t, 20 * 60, 120 * 60);
            String corr = ids.uuid4();
            schedule(when, s -> onWithdrawn(s, vehicle, "BREAKDOWN", corr));
        } else if (vehicle.battery < rules.batteryLowThreshold) {
            vehicle.pendingMaintenance = true;
            schedule(t + dice.between(30, 120), s -> onBatteryLow(s, vehicle));
        } else if (vehicle.usagesSinceService >= rules.inspectionAfterUsages) {
            vehicle.pendingMaintenance = true;
            long when = workingTime(t, 20 * 60, 120 * 60);
            String corr = ids.uuid4();
            schedule(when, s -> onWithdrawn(s, vehicle, "EXCESS_USAGE", corr));
        }
    }

    private void onBatteryLow(long t, Vehicle vehicle) {
        vehicle.state = "LOW_BATTERY";
        String corr = ids.uuid4();   // empieza un flujo nuevo: el de mantenimiento
        double[] location = World.randomPoint(zoneById.get(vehicle.zoneId), dice);
        emit(factory.batteryLow(t, corr, vehicle, rules.batteryLowThreshold, location));
        schedule(workingTime(t, 20 * 60, 120 * 60), s -> onWithdrawn(s, vehicle, "LOW_BATTERY", corr));
    }

    private void onWithdrawn(long t, Vehicle vehicle, String reason, String corr) {
        User technician = dice.pick(technicians);
        String orderId = ids.sequential("mnt", 6);
        vehicle.state = "WITHDRAWN";
        vehicle.withdrawnAt = t;
        emit(factory.withdrawn(t, corr, vehicle, orderId, reason, technician.userId));

        List<String> work = switch (reason) {
            case "LOW_BATTERY" -> List.of("RECHARGE");
            case "BREAKDOWN" -> vehicle.battery < 50 ? List.of("REPAIR", "RECHARGE") : List.of("REPAIR");
            default -> List.of("INSPECTION");
        };
        long busy = 0;
        for (String task : work) {
            int[] range = WORK_DURATION_SECONDS.get(task);
            busy += dice.between(range[0], range[1]);
        }
        long back = workingTime(t + busy, 0, 0);
        schedule(back, s -> onReturned(s, vehicle, orderId, work, corr));
    }

    private void onReturned(long t, Vehicle vehicle, String orderId, List<String> work, String corr) {
        if (work.contains("RECHARGE")) {
            vehicle.battery = 100.0;
        }
        if (work.contains("INSPECTION") || work.contains("REPAIR")) {   // recargar no cuenta como revisión
            vehicle.usagesSinceService = 0;
        }
        vehicle.state = "AVAILABLE";
        vehicle.pendingMaintenance = false;
        emit(factory.returned(t, corr, vehicle, orderId, work, t - vehicle.withdrawnAt));
        vehicle.withdrawnAt = null;
    }

    // ================================================================ foto horaria

    /**
     * Al final de cada hora apunta, por zona, disponibles y demanda: la verdad con la que comprobar la
     * analítica. "Disponibles" son los vehículos en estado AVAILABLE, es decir, lo que vería el servicio de
     * disponibilidad a partir de los eventos (una avería aún no retirada cuenta como disponible).
     */
    private void onHourEnd(long hourIndex) {
        for (Zone zone : zones) {
            int available = (int) vehicles.stream()
                    .filter(v -> v.zoneId.equals(zone.zoneId()) && "AVAILABLE".equals(v.state))
                    .count();
            HourZone slot = new HourZone(hourIndex, zone.zoneId());
            int demand = signals.getOrDefault(slot, 0);
            zoneHours.add(new ZoneHour(factory.ts(hourIndex * HOUR), zone.zoneId(), available, demand,
                    unmet.getOrDefault(slot, 0),
                    available < rules.hotspotMaxAvailable && demand > rules.hotspotMinSignals));
        }
    }
}
