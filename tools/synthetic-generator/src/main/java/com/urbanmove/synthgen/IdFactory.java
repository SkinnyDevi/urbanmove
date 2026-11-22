package com.urbanmove.synthgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Identificadores reproducibles. */
public final class IdFactory {
    private final Random rng;
    private final Map<String, Integer> counters = new HashMap<>();

    public IdFactory(Random rng) {
        this.rng = rng;
    }

    public String uuid4() {
        long high = rng.nextLong();
        long low = rng.nextLong();
        high = (high & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        low = (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low).toString();
    }

    public String sequential(String prefix, int width) {
        int next = counters.merge(prefix, 1, Integer::sum);
        return prefix + "-" + String.format("%0" + width + "d", next);
    }
}
