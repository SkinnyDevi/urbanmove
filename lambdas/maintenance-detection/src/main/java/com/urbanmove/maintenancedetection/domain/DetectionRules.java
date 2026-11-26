package com.urbanmove.maintenancedetection.domain;

import java.util.List;
import java.util.Optional;

/** Conjunto de reglas: la primera que decide abrir una orden gana; se amplía añadiendo reglas. */
public final class DetectionRules implements DetectionRule {
    private final List<DetectionRule> rules;

    public DetectionRules(List<DetectionRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public Optional<MaintenanceRequest> evaluate(VehicleSignal signal) {
        return rules.stream()
                .map(rule -> rule.evaluate(signal))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
