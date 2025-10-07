package cc4p1.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;

class PartitionerTest {

  @Test
  void deterministicForSameId() {
    Partitioner p = new Partitioner(3);
    assertEquals(p.partForId(1234L), p.partForId(1234L));
    assertEquals(p.partForId(-5L),   p.partForId(-5L));
  }

  @Test
  void inRange() {
    Partitioner p = new Partitioner(5);
    long[] ids = {0L, 1L, 2L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
    for (long id : ids) {
      int part = p.partForId(id);
      assertTrue(part >= 0 && part < 5, "part fuera de rango: " + part);
    }
  }

  @Test
  void roughlyUniformForSequentialIds() {
    int N = 3;
    Partitioner p = new Partitioner(N);
    int[] counts = new int[N];
    int SAMPLES = 100_000;

    for (long id = 1; id <= SAMPLES; id++) {
      counts[p.partForId(id)]++;
    }

    double ideal = SAMPLES / (double) N;
    // tolerancia ±2.5%
    for (int c : counts) {
      double diff = Math.abs(c - ideal) / ideal;
      assertTrue(diff < 0.025, "desbalance > 2.5%: " + Arrays.toString(counts));
    }
  }

  @Test
  void stringKeyAlsoWorks() {
    Partitioner p = new Partitioner(7);
    int a = p.partForKey("cuenta:123");
    int b = p.partForKey("cuenta:123");
    int c = p.partForKey("cuenta:124");
    assertEquals(a, b);
    assertNotEquals(a, c);
    assertTrue(a >= 0 && a < 7);
    assertTrue(c >= 0 && c < 7);
  }
}
