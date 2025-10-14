package cc4p1.storage.replicated;

import cc4p1.model.Account;
import cc4p1.storage.Partitioner;
import cc4p1.storage.Storage;
import cc4p1.storage.replicas.ReplicaSelector;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

public final class ReplicatedStorage implements Storage {
  private static final String TABLE_CUENTAS = "cuentas";
  private final Partitioner partitioner;
  private final ReplicaSelector replicas;
  private final Map<String, NodeStorageClient> nodes; // nodeId -> client

  public ReplicatedStorage(Partitioner partitioner, ReplicaSelector replicas, Map<String, NodeStorageClient> nodes) {
    this.partitioner = partitioner;
    this.replicas = replicas;
    this.nodes = nodes;
  }

  private List<NodeStorageClient> readOrder(String table, int p) {
    List<String> ids = replicas.readOrder(table, p);
    List<NodeStorageClient> out = new ArrayList<>(ids.size());
    for (String id : ids) {
      NodeStorageClient cli = nodes.get(id);
      if (cli == null) throw new IllegalStateException("No client for nodeId="+id);
      out.add(cli);
    }
    return out;
  }

  // --- GET: primario -> réplica1 -> réplica2 ---
  @Override public Optional<Account> getCuenta(long idCuenta) {
    int p = partitioner.partForId(idCuenta);
    for (NodeStorageClient cli : readOrder(TABLE_CUENTAS, p)) {
      try {
        Optional<Account> r = cli.getCuenta(idCuenta);
        if (r.isPresent()) return r;
      } catch (RuntimeException ignore) { /* probar siguiente réplica */ }
    }
    return Optional.empty();
  }

  // --- PUT: fanout a las 3 réplicas (política simple de S1) ---
  @Override public void putCuenta(Account acc) {
    int p = partitioner.partForId(acc.id());
    boolean anyOk = false; RuntimeException last = null;
    for (NodeStorageClient cli : readOrder(TABLE_CUENTAS, p)) {
      try { cli.putCuenta(acc); anyOk = true; }
      catch (RuntimeException e) { last = e; }
    }
    if (!anyOk && last != null) throw last; // si todas fallan, error
  }

  // --- SCAN: un solo nodo por partición (el primario); si falla, siguiente réplica ---
  public Stream<Account> scanCuentas() {
    Stream<Account> total = Stream.empty();
    for (int p = 0; p < partitioner.numParts(); p++) {
      Stream<Account> s = Stream.empty();
      for (NodeStorageClient cli : readOrder(TABLE_CUENTAS, p)) {
        try { s = cli.scanCuentasPartition(p); break; }
        catch (RuntimeException ignore) { /* intenta la siguiente réplica */ }
      }
      total = Stream.concat(total, s);
    }
    return total;
  }

  @Override public java.util.stream.Stream<cc4p1.model.Transaction> getTransaccionesByCuenta(long id){ throw new UnsupportedOperationException(); }
  @Override public java.util.stream.Stream<cc4p1.model.Loan> getPrestamosByCliente(long id){ throw new UnsupportedOperationException(); }
@Override
public void appendTransaccion(cc4p1.model.Transaction tx) {
  int p = partitioner.partForId(tx.origen());
  boolean anyOk = false; RuntimeException last = null;

  for (var cli : readOrder("transacciones", p)) { // usa la tabla "transacciones" en replicas.properties
    try {
      ((cc4p1.storage.replicated.LocalFileNodeStorageClient) cli).appendTransaccion(tx);
      anyOk = true;
    } catch (RuntimeException e) {
      last = e; // intenta siguiente réplica
    }
  }
  if (!anyOk && last != null) throw last; // si todas fallan, error
}
  @Override public BigDecimal arqueoSaldos() {
    return scanCuentas().map(Account::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
  
    public void putPrestamo(cc4p1.model.Loan loan){
      int p = partitioner.partForId(loan.idCliente());
      boolean anyOk = false; RuntimeException last = null;
      for (var cli : readOrder("prestamos", p)) {
        try {
          ((cc4p1.storage.replicated.LocalFileNodeStorageClient)cli).putPrestamo(loan);
          anyOk = true;
        } catch (RuntimeException e) { last = e; }
      }
      if (!anyOk && last != null) throw last;
    }

    public void appendPago(cc4p1.model.Payment pay){
      int p = partitioner.partForId(pay.idPrestamo());
      boolean anyOk = false; RuntimeException last = null;
      for (var cli : readOrder("pagos", p)) {
        try {
          ((cc4p1.storage.replicated.LocalFileNodeStorageClient)cli).appendPago(pay);
          anyOk = true;
        } catch (RuntimeException e) { last = e; }
      }
      if (!anyOk && last != null) throw last;
    }

}
