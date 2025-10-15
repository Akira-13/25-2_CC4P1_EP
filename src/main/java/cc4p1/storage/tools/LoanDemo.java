/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.storage.tools;

/**
 *
 * @author ak13a
 */
import cc4p1.model.Loan;
import cc4p1.model.LoanUtils;
import cc4p1.model.Payment;
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
import java.util.UUID;

public class LoanDemo {

  private static final int NUM_PARTS = 3;
  private static final Path BASE = Path.of("data");
  private static final Path META_DIR = BASE.resolve("metadata");
  private static final Path PROPS = META_DIR.resolve("replicas.properties");

  public static void main(String[] args) throws Exception {
    setupDirs();
    copyReplicasTemplateIfMissing(); // asegura prestamos.* y pagos.* en data/metadata
    boolean overwrite = true; // o controla con -Dreplicas.overwrite=true

    // 1) Wiring replicado
    Partitioner partitioner = new Partitioner(NUM_PARTS);
    var selector = new ReplicaSelectorProperties(PROPS);
    Map<String, NodeStorageClient> nodes = new HashMap<>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), NUM_PARTS));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), NUM_PARTS));
    Storage st = new ReplicatedStorage(partitioner, selector, nodes);

    // 2) Crear préstamo (particiona por idCliente)
    var loan = new Loan(
        1001L,              // idPrestamo
        222L,               // idCliente
        new BigDecimal("1000"), // principal
        new BigDecimal("0.25"), // tasa anual (no usada en P1)
        LocalDate.now(),
        "ACTIVO"
    );
    // Fanout a 3 réplicas
    ((ReplicatedStorage) st).putPrestamo(loan);
    System.out.println("Prestamo creado: " + loan);

    // 3) Registrar pagos (particiona por idPrestamo)
    var p1 = new Payment(UUID.randomUUID().toString(), System.currentTimeMillis(), 1001L, new BigDecimal("300"));
    var p2 = new Payment(UUID.randomUUID().toString(), System.currentTimeMillis(), 1001L, new BigDecimal("200"));
    ((ReplicatedStorage) st).appendPago(p1);
    ((ReplicatedStorage) st).appendPago(p2);
    System.out.println("Pagos agregados: 300 y 200");

    // 4) Calcular saldo pendiente (tomamos pagos desde nodeA; cualquier nodo sirve)
    var nodeA = new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS);
    var pagosStream = nodeA.getPagosByPrestamo(1001L);
    var pendiente = LoanUtils.saldoPendiente(loan, pagosStream);
    System.out.println("Saldo pendiente esperado=500, calculado=" + pendiente);
  }

  // --- utilidades de preparación ---

  private static void setupDirs() throws IOException {
    for (String n : List.of("nodeA", "nodeB", "nodeC")) {
      Files.createDirectories(BASE.resolve(n).resolve("partitions"));
    }
    Files.createDirectories(META_DIR);
  }

  private static void copyReplicasTemplateIfMissing() throws IOException {
    if (Files.exists(PROPS)) return;
    try (var in = Thread.currentThread().getContextClassLoader()
        .getResourceAsStream("templates/replicas.properties")) { // o replicas.template.properties
      if (in == null) {
        // fallback mínimo por si el template no está en el jar
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
        Files.writeString(PROPS, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        System.out.println("replicas.properties generado por fallback (sin template).");
      } else {
        Files.copy(in, PROPS);
        System.out.println("replicas.properties copiado desde template.");
      }
    }
  }
}