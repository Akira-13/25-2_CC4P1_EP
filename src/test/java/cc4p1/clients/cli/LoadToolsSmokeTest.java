package cc4p1.clients.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class LoadToolsSmokeTest {
    @Test
    public void testUniformRange() {
        DistributionUtils d = new DistributionUtils(123);
        for (int i = 0; i < 1000; i++) {
            double v = d.uniform(10, 20);
            assertTrue(v >= 10 && v <= 20, "fuera de rango");
        }
    }

    @Test
    public void testReporterSummary() {
        LoadTestReporter rep = new LoadTestReporter();
        rep.start();
        long t = System.nanoTime();
        rep.add(new LoadTestReporter.ResultRow(t, t + 1_000_000, 200, "op", "id", 12.3, "ok"));
        rep.add(new LoadTestReporter.ResultRow(t, t + 2_000_000, 500, "op", "id2", 10.0, "err"));
        rep.stop();
        var s = rep.summarize();
        assertEquals(2, s.total);
        assertEquals(1, s.ok);
        assertEquals(1, s.fail);
        assertTrue(s.p50 > 0);
    }
}
