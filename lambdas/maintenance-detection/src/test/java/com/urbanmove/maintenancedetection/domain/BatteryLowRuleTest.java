package com.urbanmove.maintenancedetection.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regla de batería baja y combinación de reglas (Strategy). */
class BatteryLowRuleTest {
    private static VehicleSignal.BatteryLow battery(int level, int threshold) {
        return new VehicleSignal.BatteryLow(new VehicleId("veh-0042"), VehicleType.SCOOTER, new ZoneId("mesa-y-lopez"),
                level, threshold);
    }

    @Test
    void belowTheThresholdOpensALowBatteryOrder() {
        MaintenanceRequest request = new BatteryLowRule().evaluate(battery(12, 15)).orElseThrow();

        assertEquals(MaintenanceReason.LOW_BATTERY, request.reason());
        assertEquals("mesa-y-lopez", request.zoneId().value());
        assertEquals("Batería al 12 % (umbral 15 %)", request.notes());
    }

    @Test
    void atOrAboveTheThresholdNothingIsNeeded() {
        assertTrue(new BatteryLowRule().evaluate(battery(15, 15)).isEmpty());
        assertTrue(new BatteryLowRule().evaluate(battery(80, 15)).isEmpty());
    }

    @Test
    void theFirstRuleThatDecidesWins() {
        MaintenanceRequest inspection = new MaintenanceRequest(new VehicleId("veh-0042"), VehicleType.SCOOTER,
                MaintenanceReason.INSPECTION, new ZoneId("tafira"), null);
        DetectionRules rules = new DetectionRules(List.of(signal -> Optional.empty(), new BatteryLowRule(),
                signal -> Optional.of(inspection)));

        assertEquals(MaintenanceReason.LOW_BATTERY, rules.evaluate(battery(5, 15)).orElseThrow().reason());
        assertEquals(MaintenanceReason.INSPECTION, rules.evaluate(battery(90, 15)).orElseThrow().reason());
    }

    @Test
    void invalidSignalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> battery(120, 15));
        assertThrows(IllegalArgumentException.class, () -> new VehicleId("bike-1"));
        assertThrows(IllegalArgumentException.class, () -> new ZoneId("Mesa Y Lopez"));
    }
}
