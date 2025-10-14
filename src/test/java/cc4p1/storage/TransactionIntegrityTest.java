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
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TransactionIntegrityTest {

  static Path BASE = Path.of("data");
  static Path META = BASE.resolve("metadata").resolve("replicas.properties");

  @BeforeAll
  static void setup() throws Exception {
    // limpia nodos y crea estructura mínima
    for (String n : List.of("nodeA","nodeB","nodeC")) {
      Path parts = BASE.resolve(n).resolve("partitions");
      if (Files.exists(parts)) try (var s = Files.walk(parts)) {
        s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
      }
      Files.createDirectories(parts);
    }
    Files.createDirectories(META.getParent());

    // replicas para cuentas y transacciones
    String props = String.join(System.lineSeparator(),
        "cuentas.p0 = nodeA,nodeB,nodeC",
        "cuentas.p1 = nodeB,nodeC,nodeA",
        "cuentas.p2 = nodeC,nodeA,nodeB",
        "transacciones.p0 = nodeA,nodeB,nodeC",
        "transacciones.p1 = nodeB,nodeC,nodeA",
        "transacciones.p2 = nodeC,nodeA,nodeB"
    ) + System.lineSeparator();
    Files.writeString(META, props, java.nio.charset.StandardCharsets.UTF_8);
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

    String txId = "TX-INTEG-NEW-1";
    long idCuenta = 123L;
    var tx = new Transaction(txId, idCuenta, "DEBITO", new BigDecimal("50"), LocalDate.now());

    // reintento: mismo id_tx y mismo payload
    st.appendTransaccion(tx);
    st.appendTransaccion(tx);

    int p = new Partitioner(3).partForId(idCuenta);
    for (String n : List.of("nodeA","nodeB","nodeC")) {
      Path f = BASE.resolve(n).resolve("partitions").resolve("transacciones_p"+p+".csv");
      assertTrue(Files.exists(f), "Falta archivo en " + n);
      long count = Files.readAllLines(f).stream().skip(1).filter(s -> s.startsWith(txId+";")).count();
      assertEquals(1, count, "Idempotencia falló en " + n);
    }
  }

  @Test
  void mismo_txid_con_payload_distinto_lanza_conflicto() {
    var st = newReplicated();

    String txId = "TX-INTEG-NEW-2";
    long idCuenta = 456L;

    var t1 = new Transaction(txId, idCuenta, "DEBITO", new BigDecimal("10.00"), LocalDate.now());
    var t2 = new Transaction(txId, idCuenta, "DEBITO", new BigDecimal("99.00"), LocalDate.now()); // cambia monto

    st.appendTransaccion(t1);
    assertThrows(IllegalStateException.class, () -> st.appendTransaccion(t2),
        "Debe fallar por conflicto de idempotencia (mismo id_tx con payload distinto)");
  }
}
