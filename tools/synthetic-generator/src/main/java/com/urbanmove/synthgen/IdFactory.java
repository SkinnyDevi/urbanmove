package com.urbanmove.synthgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Identificadores reproducibles.
 *
 * <p>UUID.randomUUID() es aleatorio de verdad: cada ejecución daría ids distintos. Aquí los UUID salen
 * del mismo generador con semilla que el resto de la simulación, así que con la misma semilla se
 * obtienen siempre los mismos ids.
 */
public final class IdFactory {

    private final Random rng;
    private final Map<String, Integer> counters = new HashMap<>();

    public IdFactory(Random rng) {
        this.rng = rng;
    }

    /** UUID versión 4 (el formato que exigen los contratos), pero salido de la semilla. */
    public String uuid4() {
        long high = rng.nextLong();
        long low = rng.nextLong();
        high = (high & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;   // versión 4
        low = (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;     // variante RFC 4122
        return new UUID(high, low).toString();
    }

    /** sequential("res", 6) -> "res-000001", "res-000002"... */
    public String sequential(String prefix, int width) {
        int next = counters.merge(prefix, 1, Integer::sum);
        return prefix + "-" + String.format("%0" + width + "d", next);
    }
}
