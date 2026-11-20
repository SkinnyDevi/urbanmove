package com.urbanmove.zones.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.application.SeedZonesUseCase;
import com.urbanmove.zones.application.ZoneNotFoundException;
import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZoneUseCasesTest {

    private static final Zone TRIANA = zone("triana");
    private static final Zone TAFIRA = zone("tafira");

    private InMemoryZoneRepository repository;
    private SeedZonesUseCase seed;

    private static Zone zone(String id) {
        return new Zone(new ZoneId(id), id.toUpperCase(), new GeoPoint(28.1, -15.4), 500, 20, List.of());
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryZoneRepository();
        seed = new SeedZonesUseCase(() -> List.of(TRIANA, TAFIRA), repository);
    }

    @Test
    void seedingLoadsEveryZone() {
        assertEquals(2, seed.execute());
        assertEquals(List.of(TAFIRA, TRIANA), new ListZonesUseCase(repository).execute(), "ordenadas por id");
    }

    @Test
    void seedingTwiceDoesNotDuplicateZones() {
        seed.execute();

        assertEquals(0, seed.execute());
        assertEquals(2, new ListZonesUseCase(repository).execute().size());
    }

    @Test
    void seedingDoesNotOverwriteExistingZones() {
        Zone edited = new Zone(new ZoneId("tafira"), "Tafira editada", new GeoPoint(28.1, -15.4), 500, 99, List.of());
        repository.saveIfAbsent(List.of(edited));

        seed.execute();

        assertEquals(99, new GetZoneUseCase(repository).execute(new ZoneId("tafira")).capacity());
    }

    @Test
    void getZoneReturnsTheZone() {
        seed.execute();
        assertEquals(TRIANA, new GetZoneUseCase(repository).execute(new ZoneId("triana")));
    }

    @Test
    void getZoneFailsWhenItDoesNotExist() {
        ZoneNotFoundException error = assertThrows(ZoneNotFoundException.class,
                () -> new GetZoneUseCase(repository).execute(new ZoneId("atlantis")));
        assertEquals(new ZoneId("atlantis"), error.zoneId());
    }
}
