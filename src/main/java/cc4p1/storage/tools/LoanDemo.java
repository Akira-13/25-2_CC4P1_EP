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
import java.util.*;

public class LoanDemo {

  private static final int NUM_PARTS = 3;
  private static final Path BASE = Path.of("data");
  private static final Path META_DIR = BASE.resolve("metadata");
  private static final Path PROPS = META_DIR.resolve("replicas.properties");

  public static void main(String[] args) throws Exception {
    setupDirs();
    copyReplicasTemplateIfMissing(); // asegura prestamos.* y pagos.* en data/metadata

    // 1) Wiring replicado
    Partitioner partitioner = new Partitioner(NUM_PARTS);
    var selector = new ReplicaSelectorProperties(PROPS);
    Map<String, NodeStorageClient> nodes = new HashMap<>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), NUM_PARTS));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), NUM_PARTS));
    Storage st = new ReplicatedStorage(partitioner, selector, nodes);

    // 2) Crear préstamo (ahora con 'pendiente' inicial = monto y estado ACTIVO)
    long idPrestamo = 1001L;
    long idCliente  = 222L;
    BigDecimal monto = new BigDecimal("1000");
    BigDecimal tasa  = new BigDecimal("0.25"); // no usada en P1
    Loan loan = Loan.newLoan(idPrestamo, idCliente, monto, tasa, LocalDate.now());
    ((ReplicatedStorage) st).putPrestamo(loan);
    System.out.println("Préstamo creado (pendiente inicial): " + loan.pendiente()); // 1000

    // 3) Registrar pagos (particionado por idPrestamo)
    var p1 = new Payment(UUID.randomUUID().toString(), System.currentTimeMillis(), idPrestamo, new BigDecimal("300"));
    var p2 = new Payment(UUID.randomUUID().toString(), System.currentTimeMillis(), idPrestamo, new BigDecimal("200"));
    ((ReplicatedStorage) st).appendPago(p1);
    ((ReplicatedStorage) st).appendPago(p2);
    System.out.println("Pagos agregados: 300 y 200");

    // 4) Recalcular pendiente y estado en base a pagos (leemos de nodeA; cualquier nodo sirve)
    var nodeA = new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), NUM_PARTS);
    var pagosStream = nodeA.getPagosByPrestamo(idPrestamo);
    Loan loanActualizado = LoanUtils.withPendienteActualizado(loan, pagosStream);
    System.out.println("Pendiente recalculado = " + loanActualizado.pendiente() +
        " | Estado = " + loanActualizado.estado()); // esperado: 500 | ACTIVO

    // 5) (Opcional) Persistir el préstamo actualizado para que el CSV tenga el 'pendiente' real
    ((ReplicatedStorage) st).putPrestamo(loanActualizado);
    System.out.println("Préstamo actualizado persistido con pendiente=" + loanActualizado.pendiente());
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
        .getResourceAsStream("templates/replicas.properties")) {
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
        Files.writeString(PROPS, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("replicas.properties generado por fallback (sin template).");
      } else {
        Files.copy(in, PROPS);
        System.out.println("replicas.properties copiado desde template.");
      }
    }
  }
}