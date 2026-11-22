package com.urbanmove.zones.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reglas de la zona y de sus identificadores. */
class ZoneTest {
    private static final GeoPoint CENTER = new GeoPoint(28.07, -15.45);

    @Test
    void aValidZoneKeepsItsData() {
        Zone zone = new Zone(new ZoneId("tafira"), "Campus de Tafira", CENTER, 600, 25, List.of("Solo en aparcabicis"));

        assertEquals("tafira", zone.id().value());
        assertEquals(600, zone.radiusMeters());
        assertEquals(List.of("Solo en aparcabicis"), zone.parkingRestrictions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Tafira", "mesa y lopez", "zona_1", "ñ"})
    void zoneIdsMustBeLowercaseSlugs(String invalid) {
        assertThrows(InvalidZoneException.class, () -> new ZoneId(invalid));
    }

    @Test
    void nameIsRequired() {
        assertThrows(InvalidZoneException.class, () -> new Zone(new ZoneId("a"), " ", CENTER, 1, 0, null));
    }

    @Test
    void radiusMustBePositiveAndCapacityNotNegative() {
        assertThrows(InvalidZoneException.class, () -> new Zone(new ZoneId("a"), "A", CENTER, 0, 0, null));
        assertThrows(InvalidZoneException.class, () -> new Zone(new ZoneId("a"), "A", CENTER, 1, -1, null));
    }

    @Test
    void coordinatesMustBeInRange() {
        assertThrows(InvalidZoneException.class, () -> new GeoPoint(91, 0));
        assertThrows(InvalidZoneException.class, () -> new GeoPoint(0, -181));
    }

    @Test
    void parkingRestrictionsCannotBeChangedFromOutside() {
        List<String> restrictions = new ArrayList<>(List.of("A"));
        Zone zone = new Zone(new ZoneId("a"), "A", CENTER, 1, 0, restrictions);

        restrictions.add("B");

        assertEquals(List.of("A"), zone.parkingRestrictions());
        assertThrows(UnsupportedOperationException.class, () -> zone.parkingRestrictions().add("C"));
    }

    @Test
    void missingParkingRestrictionsMeansNone() {
        assertEquals(List.of(), new Zone(new ZoneId("a"), "A", CENTER, 1, 0, null).parkingRestrictions());
    }
}
