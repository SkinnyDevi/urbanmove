package com.urbanmove.synthgen;

import java.util.List;
import java.util.Random;

/** Todas las decisiones al azar de la simulación pasan por aquí. */
public final class Dice {
    private final Random rng;

    public Dice(Random rng) {
        this.rng = rng;
    }

    public boolean chance(double p) {
        return rng.nextDouble() < p;
    }

    public double nextDouble() {
        return rng.nextDouble();
    }

    public double uniform(double min, double max) {
        return min + (max - min) * rng.nextDouble();
    }

    public int between(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    public int below(int bound) {
        return rng.nextInt(bound);
    }

    public <T> T pick(List<T> items) {
        return items.get(rng.nextInt(items.size()));
    }

    public <T> T weighted(List<T> items, double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double target = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < items.size(); i++) {
            acc += weights[i];
            if (target < acc) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    public int poisson(double mean) {
        if (mean <= 0) {
            return 0;
        }
        double limit = Math.exp(-mean);
        double p = 1.0;
        int k = 0;
        while (true) {
            p *= rng.nextDouble();
            if (p <= limit) {
                return k;
            }
            k++;
        }
    }
}
