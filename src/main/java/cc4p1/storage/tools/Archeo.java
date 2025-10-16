package cc4p1.storage.tools;

import cc4p1.storage.FileStorage;
import cc4p1.model.Account;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Arqueo de saldos para demostrar conservación de dinero.
 *
 * Uso:
 * java -cp target/classes cc4p1.storage.tools.Archeo
 * --paths=data/nodeA;data/nodeB --parts=3
 *
 * - Si no se pasa --paths, detecta automáticamente subdirectorios bajo "data"
 * (por ejemplo nodeA, nodeB, nodeC).
 * - Deduplica por id_cuenta para evitar doble conteo si hay réplicas.
 */
public final class Archeo {
  public static void main(String[] args) {
    Map<String, String> opts = parseArgs(args);
    int parts = Integer.parseInt(opts.getOrDefault("parts", "3"));

    List<Path> bases = new ArrayList<>();
    String pathsOpt = opts.get("paths");
    if (pathsOpt != null && !pathsOpt.isBlank()) {
      for (String p : pathsOpt.split(";")) {
        if (!p.isBlank())
          bases.add(Path.of(p.trim()));
      }
    } else {
      // Autodetecta bajo data/*
      Path data = Path.of("data");
      try {
        if (Files.isDirectory(data)) {
          try (var stream = Files.list(data)) {
            stream.filter(Files::isDirectory).forEach(bases::add);
          }
        }
      } catch (Exception ignore) {
      }
      if (bases.isEmpty()) {
        // fallback a data/nodeA si existe; sino a data
        Path nodeA = Path.of("data", "nodeA");
        bases.add(Files.exists(nodeA) ? nodeA : Path.of("data"));
      }
    }

    // Acumula por id de cuenta (evita doble conteo si hay réplicas)
    Map<Long, BigDecimal> saldos = new HashMap<>();
    int cuentasLeidas = 0;

    for (Path base : bases) {
      FileStorage fs = FileStorage.open(base, parts);
      for (int p = 0; p < parts; p++) {
        try (var stream = fs.scanCuentasPartition(p)) {
          for (Account a : (Iterable<Account>) stream::iterator) {
            // última escritura gana: asumimos réplicas consistentes, por lo que no debería
            // cambiar
            saldos.put(a.id(), a.saldo());
            cuentasLeidas++;
          }
        } catch (Exception ignore) {
        }
      }
    }

    BigDecimal total = saldos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    System.out.println("ARQUEO_TOTAL=" + total);
    System.out.println("CUENTAS_UNICAS=" + saldos.size());
    System.out.println("FILAS_LEIDAS=" + cuentasLeidas + " BASES=" + bases);
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.startsWith("--")) {
        String kv = a.substring(2);
        int eq = kv.indexOf('=');
        if (eq > 0)
          m.put(kv.substring(0, eq), kv.substring(eq + 1));
        else if (i + 1 < args.length)
          m.put(kv, args[++i]);
      }
    }
    return m;
  }
}
