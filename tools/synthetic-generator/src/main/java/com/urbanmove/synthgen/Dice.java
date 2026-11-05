package com.urbanmove.synthgen;

import java.util.List;
import java.util.Random;

/**
 * Todas las decisiones al azar de la simulación pasan por aquí. Usa java.util.Random, cuyo algoritmo está
 * fijado por la especificación de Java: con la misma semilla da los mismos números en cualquier ordenador.
 */
public final class Dice {

    private final Random rng;

    public Dice(Random rng) {
        this.rng = rng;
    }

    /** true con probabilidad p (por ejemplo chance(0.7) es true el 70 % de las veces). */
    public boolean chance(double p) {
        return rng.nextDouble() < p;
    }

    public double nextDouble() {
        return rng.nextDouble();
    }

    /** Número decimal entre min y max. */
    public double uniform(double min, double max) {
        return min + (max - min) * rng.nextDouble();
    }

    /** Número entero entre min y max, ambos incluidos. */
    public int between(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    /** Número entero entre 0 y bound - 1. */
    public int below(int bound) {
        return rng.nextInt(bound);
    }

    public <T> T pick(List<T> items) {
        return items.get(rng.nextInt(items.size()));
    }

    /** Elige un elemento con probabilidad proporcional a su peso. */
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

    /**
     * Cuántas veces ocurre algo cuando de media ocurre "mean" veces (distribución de Poisson,
     * algoritmo de Knuth). Por ejemplo, si de media llegan 9 personas, unas horas llegan 7 y otras 12.
     */
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
