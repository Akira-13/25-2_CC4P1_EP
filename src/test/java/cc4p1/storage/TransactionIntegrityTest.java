/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.storage;

/**
 *
 * @author ak13a
 */
import cc4p1.model.Transaction;
import cc4p1.storage.replicas.ReplicaSelectorProperties;
import cc4p1.storage.replicated.LocalFileNodeStorageClient;
import cc4p1.storage.replicated.NodeStorageClient;
import cc4p1.storage.replicated.ReplicatedStorage;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TransactionIntegrityTest {

  static Path BASE = Path.of("data");
  static Path META = BASE.resolve("metadata").resolve("replicas.properties");

  @BeforeAll
  static void setup() throws Exception {
    // limpia y crea estructura mínima
    for (String n : List.of("nodeA","nodeB","nodeC")) {
      Path parts = BASE.resolve(n).resolve("partitions");
      if (Files.exists(parts)) try (var s = Files.walk(parts)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
      }
      Files.createDirectories(parts);
    }
    Files.createDirectories(META.getParent());
    // replicas completas para cuentas y transacciones
    String props = String.join(System.lineSeparator(),
        "cuentas.p0 = nodeA,nodeB,nodeC",
        "cuentas.p1 = nodeB,nodeC,nodeA",
        "cuentas.p2 = nodeC,nodeA,nodeB",
        "transacciones.p0 = nodeA,nodeB,nodeC",
        "transacciones.p1 = nodeB,nodeC,nodeA",
        "transacciones.p2 = nodeC,nodeA,nodeB"
    ) + System.lineSeparator();
    Files.writeString(META, props);
  }

  private ReplicatedStorage newReplicated() {
    var part = new Partitioner(3);
    var selector = new ReplicaSelectorProperties(META);
    var nodes = new HashMap<String, NodeStorageClient>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), 3));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), 3));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), 3));
    return new ReplicatedStorage(part, selector, nodes);
  }

  @Test
  void reintentos_misma_txid_no_duplican_registro() throws Exception {
    var st = newReplicated();

    String txId = "TX-INTEG-1";
    long origen = 123L, destino = 456L;
    var tx = new Transaction(txId, System.currentTimeMillis(), origen, destino, new BigDecimal("50"), "TRANSFER");

    // reintento: mismo txId y mismo payload
    st.appendTransaccion(tx);
    st.appendTransaccion(tx);

    int p = new Partitioner(3).partForId(origen);
    for (String n : List.of("nodeA","nodeB","nodeC")) {
      Path f = BASE.resolve(n).resolve("partitions").resolve("transacciones_p"+p+".csv");
      long count = Files.readAllLines(f).stream().skip(1).filter(s -> s.startsWith(txId+";")).count();
      assertEquals(1, count, "idempotencia falló en " + n);
    }
  }
}
