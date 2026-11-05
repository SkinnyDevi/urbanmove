package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/** Todo lo que produce una simulación, listo para escribirse en disco o comprobarse en los tests. */
public record GenerationResult(Settings settings, Rules rules, List<Zone> zones, List<Vehicle> vehicles,
                               List<User> users, List<ObjectNode> events, List<ZoneHour> zoneHours,
                               Map<HourZone, Integer> unmetDemand) {
}
