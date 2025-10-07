package cc4p1.storage;

/**
 * Particionador determinista para IDs long y claves String.
 * Usa una mezcla 64-bit tipo SplitMix64 para mejor distribución.
 */
public final class Partitioner {
  private final int numParts;

  public Partitioner(int numParts) {
    if (numParts <= 0) throw new IllegalArgumentException("numParts must be > 0");
    this.numParts = numParts;
  }

  public int numParts() { return numParts; }

  /** Partición para un ID (long). Rango: [0..numParts-1] */
  public int partForId(long id) {
    long h = hash64(id);
    // índice no negativo con aritmética sin signo
    return (int) (Long.remainderUnsigned(h, (long) numParts));
  }

  /** Variante para claves String (si la llegas a necesitar). */
  public int partForKey(String key) {
    if (key == null) throw new NullPointerException("key");
    long h = 1469598103934665603L;               // FNV offset basis
    for (int i = 0; i < key.length(); i++) {
      h ^= key.charAt(i);
      h *= 1099511628211L;                       // FNV prime
    }
    h = mix64(h);
    return (int) (Long.remainderUnsigned(h, (long) numParts));
  }

  /** Hash 64-bit estable para long. */
  private static long hash64(long x) {
    return mix64(x + 0x9E3779B97F4A7C15L);       // "golden ratio" salt
  }

  /** Mezcla 64-bit (SplitMix64). */
  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    z =  z ^ (z >>> 31);
    return z;
  }
}
