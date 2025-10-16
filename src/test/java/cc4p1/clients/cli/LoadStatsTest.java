package cc4p1.clients.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LoadStatsTest {
    @Test
    public void testPercentilesDeterministic() {
        // Construye latencias 1..100 microsegundos y verifica percentiles exactos
        LoadTestReporter rep = new LoadTestReporter();
        rep.start();
        long start = 0L;
        for (int us = 1; us <= 100; us++) {
            long end = start + (us * 1_000L); // 1 microsegundo = 1000 ns
            rep.add(new LoadTestReporter.ResultRow(start, end, 200, "op", "id"+us, 10.0, "ok"));
            start += 2_000_000L; // separar ventanas en ns para evitar solapes
        }
        rep.stop();
        var s = rep.summarize();
        // Con n=100 y el cálculo idx = round(p*(n-1)), esperamos:
        // p50 -> idx=round(0.5*99)=50 => valor 51
        // p90 -> idx=round(0.9*99)=89 => valor 90
        // p95 -> idx=round(0.95*99)=94 => valor 95
        // p99 -> idx=round(0.99*99)=98 => valor 99
        assertEquals(100, s.total);
        assertEquals(51, s.p50);
        assertEquals(90, s.p90);
        assertEquals(95, s.p95);
        assertEquals(99, s.p99);
        assertEquals(100, s.ok);
        assertEquals(0, s.fail);
    }

    @Test
    public void testUniformMeanAndStdDev() {
        // Muestrea uniforme [50,500] y valida media y desviación dentro de tolerancias razonables
        DistributionUtils d = new DistributionUtils(42);
        int N = 20_000;
        double min = 50.0, max = 500.0;
        double sum = 0.0, sum2 = 0.0;
        for (int i = 0; i < N; i++) {
            double v = d.uniform(min, max);
            assertTrue(v >= min && v <= max, "fuera de rango");
            sum += v;
            sum2 += v * v;
        }
        double mean = sum / N;
        double var = (sum2 / N) - (mean * mean);
        double std = Math.sqrt(Math.max(0.0, var));

        double expectedMean = (min + max) / 2.0; // 275.0
        double expectedStd = (max - min) / Math.sqrt(12.0); // ~129.9

        // Tolerancias: media dentro de 1% del rango; std dentro de 10%
        double meanTol = (max - min) * 0.01; // 1% del rango = 4.5
        double stdTol = expectedStd * 0.10;  // 10%

        assertEquals(expectedMean, mean, meanTol, "media fuera de tolerancia");
        assertEquals(expectedStd, std, stdTol, "desviación estándar fuera de tolerancia");
    }
}
