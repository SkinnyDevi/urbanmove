package com.urbanmove.zones.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.infrastructure.seed.JsonZoneSeedSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class JsonZoneSeedSourceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<Zone> load(String json) {
        return new JsonZoneSeedSource(new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)), JSON).load();
    }

    @Test
    void theBundledSeedIsValid() {
        List<Zone> zones = new JsonZoneSeedSource(new ClassPathResource("seed/zones.json"), JSON).load();

        assertFalse(zones.isEmpty());
        assertTrue(zones.stream().anyMatch(z -> z.id().value().equals("tafira")));
        assertEquals(zones.size(), zones.stream().map(Zone::id).distinct().count(), "ids repetidos en la semilla");
    }

    @Test
    void generatorOnlyFieldsAreIgnored() {
        List<Zone> zones = load("""
                [{"zoneId":"tafira","name":"Tafira","center":{"lat":28.07,"lon":-15.45},
                  "radiusMeters":600,"capacity":25,"demandProfile":"campus","popularity":1.2}]""");

        assertEquals(1, zones.size());
        assertEquals(List.of(), zones.get(0).parkingRestrictions());
    }

    @Test
    void parkingRestrictionsAreRead() {
        List<Zone> zones = load("""
                [{"zoneId":"a","name":"A","center":{"lat":0,"lon":0},"radiusMeters":1,"capacity":0,
                  "parkingRestrictions":["Prohibido en acera"]}]""");

        assertEquals(List.of("Prohibido en acera"), zones.get(0).parkingRestrictions());
    }

    @Test
    void aMissingFieldIsReportedClearly() {
        InvalidZoneException error = assertThrows(InvalidZoneException.class,
                () -> load("[{\"zoneId\":\"a\",\"center\":{\"lat\":0,\"lon\":0},\"radiusMeters\":1,\"capacity\":0}]"));
        assertTrue(error.getMessage().contains("name"));
    }

    @Test
    void theSeedMustBeAnArray() {
        assertThrows(InvalidZoneException.class, () -> load("{\"zoneId\":\"a\"}"));
    }
}
