package cc4p1.clients.cli;

import java.util.Random;

/**
 * Utilidades para muestrear montos según distintas distribuciones.
 * Sin dependencias externas; usa java.util.Random.
 */
public final class DistributionUtils {
    private final Random rnd;

    public DistributionUtils(long seed) {
        this.rnd = new Random(seed);
    }

    public static DistributionUtils create() {
        return new DistributionUtils(System.nanoTime());
    }

    /**
     * Uniforme en [min, max]. Si min == max, retorna min.
     */
    public double uniform(double min, double max) {
        if (max < min) { double t = min; min = max; max = t; }
        if (max == min) return min;
        return min + (max - min) * rnd.nextDouble();
    }

    /**
     * Normal con media y desviación estándar dadas. Sin truncar.
     */
    public double normal(double mean, double stdDev) {
        if (stdDev <= 0) return mean;
        return mean + stdDev * rnd.nextGaussian();
    }

    /**
     * Log-normal a partir de mu, sigma de la normal subyacente.
     */
    public double logNormal(double mu, double sigma) {
        if (sigma <= 0) return Math.exp(mu);
        double g = mu + sigma * rnd.nextGaussian();
        return Math.exp(g);
    }

    /**
     * Uniforme entero en [min, max] inclusive.
     */
    public long uniformLong(long min, long max) {
        if (max < min) { long t = min; min = max; max = t; }
        if (max == min) return min;
        long bound = (max - min) + 1;
        long v = Math.abs(rnd.nextLong());
        return min + (v % bound);
    }
}
