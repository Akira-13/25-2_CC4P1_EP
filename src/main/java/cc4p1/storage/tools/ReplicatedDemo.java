package cc4p1.storage.tools;

import cc4p1.model.Account;
import cc4p1.storage.Partitioner;
import cc4p1.storage.Storage;
import cc4p1.storage.replicas.ReplicaSelectorProperties;
import cc4p1.storage.replicated.LocalFileNodeStorageClient;
import cc4p1.storage.replicated.NodeStorageClient;
import cc4p1.storage.replicated.ReplicatedStorage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplicatedDemo {

  private static final int NUM_PARTS = 3;
  private static final Path BASE = Path.of("data");

  public static void main(String[] args) throws Exception {
    // 0) Asegura estructura mínima
    for (String n : List.of("nodeA","nodeB","nodeC")) {
      Files.createDirectories(BASE.resolve(n).resolve("partitions"));
    }
    Path meta = BASE.resolve("metadata");
    Files.createDirectories(meta);
    Path props = meta.resolve("replicas.properties");
    if (!Files.exists(props)) {
      String content = String.join(System.lineSeparator(),
        "cuentas.p0 = nodeA,nodeB,nodeC",
        "cuentas.p1 = nodeB,nodeC,nodeA",
        "cuentas.p2 = nodeC,nodeA,nodeB") + System.lineSeparator();
      Files.writeString(props, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    // 1) Wiring del storage replicado
    Partitioner partitioner = new Partitioner(NUM_PARTS);
    var selector = new ReplicaSelectorProperties(props);

    Map<String, NodeStorageClient> nodes = new HashMap<>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), NUM_PARTS));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), NUM_PARTS));

    Storage st = new ReplicatedStorage(partitioner, selector, nodes);

    // 2) Prueba rápida: put (fanout), get (failover implícito), scan (sin duplicados)
    Account a = new Account(42L, 42L, BigDecimal.valueOf(1000), LocalDate.now());
    st.putCuenta(a);
    System.out.println("PUT OK");

    System.out.println("GET 42 -> presente? " + st.getCuenta(42L).isPresent());
    long scanCount = ((ReplicatedStorage) st).scanCuentas().count();
    System.out.println("SCAN cuentas = " + scanCount);
  }
}
