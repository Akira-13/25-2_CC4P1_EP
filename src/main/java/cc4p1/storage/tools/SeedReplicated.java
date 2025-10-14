package cc4p1.storage.tools;

import cc4p1.model.Account;
import cc4p1.storage.Partitioner;
import cc4p1.storage.Storage;
import cc4p1.storage.replicas.ReplicaSelectorProperties;
import cc4p1.storage.replicated.LocalFileNodeStorageClient;
import cc4p1.storage.replicated.NodeStorageClient;
import cc4p1.storage.replicated.ReplicatedStorage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SeedReplicated {

  private static final int NUM_PARTS = 3;
  private static final Path BASE = Path.of("data");
  private static final Path META = BASE.resolve("metadata");
  private static final Path PROPS = META.resolve("replicas.properties");

  public static void main(String[] args) throws Exception {
    int n = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
    setupDirsOnly();                 // ← crea solo carpetas
    copyReplicasTemplatePrefer();    // ← crea/actualiza el archivo desde resources

    // 1) Particionador y selector
    Partitioner partitioner = new Partitioner(NUM_PARTS);
    var selector = new ReplicaSelectorProperties(PROPS); // solo JDK (java.util.Properties)

    // 2) Clientes por nodo (cada uno envuelve FileStorage apuntando a su root)
    Map<String, NodeStorageClient> nodes = new HashMap<>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), NUM_PARTS));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), NUM_PARTS));

    // 3) Storage replicado
    Storage st = new ReplicatedStorage(partitioner, selector, nodes);

    // 4) Sembrar N cuentas replicadas
    BigDecimal total = BigDecimal.ZERO;
    for (long id = 1; id <= n; id++) {
      BigDecimal saldo = BigDecimal.valueOf(1000); // o genera una distribución si quieres
      st.putCuenta(new Account(id, id, saldo, LocalDate.now()));
      total = total.add(saldo);
    }

    System.out.println("SEED_REPLICATED_OK cuentas=" + n + " TOTAL_SEED=" + total);

    // 5) (Opcional) Verificación de “no duplicados” con scan del ReplicatedStorage
    long count = ((ReplicatedStorage) st).scanCuentas().count();
    System.out.println("SCAN_COUNT (no duplicados) = " + count);
  }

  private static void setupDirsAndReplicasFileIfMissing() throws IOException {
    // Crea árboles de nodos si no existen
    for (String n : List.of("nodeA", "nodeB", "nodeC")) {
      Files.createDirectories(BASE.resolve(n).resolve("partitions"));
    }
    Files.createDirectories(META);

    // Si no existe replicas.properties, escribe uno por defecto (primario y dos réplicas por partición)
    if (!Files.exists(PROPS)) {
      String content = String.join(System.lineSeparator(),
          "cuentas.p0 = nodeA,nodeB,nodeC",
          "cuentas.p1 = nodeB,nodeC,nodeA",
          "cuentas.p2 = nodeC,nodeA,nodeB"
      ) + System.lineSeparator();
      Files.writeString(PROPS, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      System.out.println("Creado " + PROPS.toString());
    }
  }
  
 private static void setupDirsOnly() throws IOException {
  for (String n : List.of("nodeA", "nodeB", "nodeC")) {
    Files.createDirectories(BASE.resolve(n).resolve("partitions"));
  }
  Files.createDirectories(META);
}
 
// 3) copiar template preferentemente (con nombre correcto y fallback)
private static void copyReplicasTemplatePrefer() throws IOException {
  boolean overwrite = Boolean.getBoolean("replicas.overwrite"); // usa -Dreplicas.overwrite=true si quieres forzar
  Files.createDirectories(META);

  // intenta cargar el template con el NOMBRE REAL en resources/templates/
  try (var in = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream("templates/replicas.properties")) { // ← ojo al nombre

    if (in != null) {
      if (overwrite || !Files.exists(PROPS)) {
        Files.copy(in, PROPS, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("replicas.properties copiado desde template (overwrite=" + overwrite + ").");
      }
      return;
    }
  }

  // Fallback: si no hay template en el classpath, genera uno completo (cuentas+transacciones+prestamos+pagos)
  if (overwrite || !Files.exists(PROPS)) {
    String content = String.join(System.lineSeparator(),
      "cuentas.p0 = nodeA,nodeB,nodeC",
      "cuentas.p1 = nodeB,nodeC,nodeA",
      "cuentas.p2 = nodeC,nodeA,nodeB",
      "transacciones.p0 = nodeA,nodeB,nodeC",
      "transacciones.p1 = nodeB,nodeC,nodeA",
      "transacciones.p2 = nodeC,nodeA,nodeB",
      "prestamos.p0 = nodeA,nodeB,nodeC",
      "prestamos.p1 = nodeB,nodeC,nodeA",
      "prestamos.p2 = nodeC,nodeA,nodeB",
      "pagos.p0 = nodeA,nodeB,nodeC",
      "pagos.p1 = nodeB,nodeC,nodeA",
      "pagos.p2 = nodeC,nodeA,nodeB"
    ) + System.lineSeparator();
    Files.writeString(PROPS, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    System.out.println("replicas.properties generado por fallback (sin template).");
  }
}

private static void copyReplicasTemplateIfMissing() throws IOException {
  Files.createDirectories(META);
  if (!Files.exists(PROPS)) {
    try (var in = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("templates/replicas.properties")) {
      if (in == null) {
        System.err.println("No se encontró templates/replicas.template.properties en resources");
        return;
      }
      Files.copy(in, PROPS);
      System.out.println("Copiado template de replicas.properties en " + PROPS);
    }
  }
}
}
