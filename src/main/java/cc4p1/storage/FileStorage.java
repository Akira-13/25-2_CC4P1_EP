package cc4p1.storage;

import cc4p1.model.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

public final class FileStorage implements Storage {
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> FILE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private Object lockFor(Path f) { return FILE_LOCKS.computeIfAbsent(f, k -> new Object()); }
    private static final String CUENTAS_HEADER = "id_cuenta;id_cliente;saldo;fecha_apertura\n";
  private final Path base; private final Partitioner partitioner;

  private FileStorage(Path base, int numParts){
    this.base = base; this.partitioner = new Partitioner(numParts);
  }
  public static FileStorage open(Path base, int numParts){
    return new FileStorage(base, numParts);
  }

    @Override
    public Optional<Account> getCuenta(long id){
      int p = partitioner.partForId(id);
      Path file = cuentasFile(p);
      try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        br.readLine(); // header
        for (String line; (line = br.readLine()) != null; ) {
          String[] f = line.split(";");
          if (Long.parseLong(f[0]) == id) return Optional.of(Account.fromCsv(f));
        }
        return Optional.empty();
      } catch (NoSuchFileException nf) {
        return Optional.empty();
      } catch(IOException e){ throw new UncheckedIOException(e); }
    }

@Override
public void putCuenta(Account acc){
  int p = partitioner.partForId(acc.id());
  Path file = cuentasFile(p);
  try {
    ensureCuentasFileWithHeader(file);
    synchronized (lockFor(file)) {
      java.util.List<String> lines = java.nio.file.Files.exists(file)
          ? java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)
          : java.util.List.of();

      java.util.List<String> out = new java.util.ArrayList<>();
      if (lines.isEmpty()) {
        out.add(CUENTAS_HEADER.strip()); // header
      } else {
        out.add(lines.get(0)); // header existente
        for (int i = 1; i < lines.size(); i++) {
          String ln = lines.get(i);
          if (ln.isBlank()) continue;
          String[] f = ln.split(";");
          long id = Long.parseLong(f[0]);
          if (id != acc.id()) out.add(ln); // filtra la cuenta que vamos a reemplazar
        }
      }
      // escribe de nuevo sin la vieja fila
      java.nio.file.Files.write(file,
          String.join("\n", out).concat("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
          java.nio.file.StandardOpenOption.CREATE);

      // append de la nueva fila
      String row = acc.toCsv() + "\n";
      java.nio.file.Files.write(file, row.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.APPEND);
    }
  } catch (java.io.IOException e){ throw new java.io.UncheckedIOException(e); }
}


    @Override
    public BigDecimal arqueoSaldos(){
      try {
        BigDecimal total = BigDecimal.ZERO;
        for (int p=0; p<partitioner.numParts(); p++){
          Path file = cuentasFile(p);
          if (!Files.exists(file)) continue;
          try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            for (String line; (line = br.readLine()) != null; ) {
              String[] f = line.split(";");
              total = total.add(new BigDecimal(f[2]));
            }
          }
        }
        return total;
      } catch(IOException e){ throw new UncheckedIOException(e); }
    }

    public java.util.stream.Stream<cc4p1.model.Account> scanCuentasPartition(int p) {
      java.nio.file.Path file = base.resolve("partitions").resolve("cuentas_p" + p + ".csv");
      if (!java.nio.file.Files.exists(file)) {
        return java.util.stream.Stream.empty();
      }
      try {
        // Cargamos todas las filas (excepto header) a memoria y devolvemos stream de la lista.
        // Ventaja: no hay que cerrar nada fuera; el stream NO depende de un Reader abierto.
        java.util.List<cc4p1.model.Account> accs =
            java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)
              .stream()
              .skip(1)                       // saltar header
              .filter(line -> !line.isBlank())
              .map(line -> line.split(";"))
              .map(cc4p1.model.Account::fromCsv)
              .toList();

        return accs.stream();
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
    
    private Path cuentasFile(int p) {
      return base.resolve("partitions").resolve("cuentas_p" + p + ".csv");
    }
    
    
    private void ensureCuentasFileWithHeader(Path file) throws IOException {
      Files.createDirectories(file.getParent());
      if (!Files.exists(file)) {
        Files.write(file, CUENTAS_HEADER.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      }
    }

  public BigDecimal arqueoSaldosPartition(int p) { return scanCuentasPartition(p).map(Account::saldo).reduce(BigDecimal.ZERO, BigDecimal::add); }

  @Override public Stream<Transaction> getTransaccionesByCuenta(long id){ return Stream.empty(); }  

  private static final String TRANSACCIONES_HEADER = "tx_id;ts;origen;destino;monto;tipo\n";

  private Path transaccionesFile(int p) {
    return base.resolve("partitions").resolve("transacciones_p" + p + ".csv");
  }

  private void ensureFileWithHeader(Path file, String header) throws IOException {
    Files.createDirectories(file.getParent());
    if (!Files.exists(file)) {
      Files.write(file, header.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
  }

  private Optional<cc4p1.model.Transaction> findTxInPartition(String txId, int p) {
    Path f = transaccionesFile(p);
    if (!Files.exists(f)) return Optional.empty();
    try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
      br.readLine(); // header
      for (String line; (line = br.readLine()) != null; ) {
        if (line.isBlank()) continue;
        String[] cols = line.split(";");
        if (cols.length < 6) continue;
        if (txId.equals(cols[0])) return Optional.of(cc4p1.model.Transaction.fromCsv(cols));
      }
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void atomicAppendLine(Path file, String lineWithNewline) throws IOException {
    // lock por archivo dentro del proceso
    synchronized (lockFor(file)) {
      ensureFileWithHeader(file, TRANSACCIONES_HEADER);
      try (FileChannel ch = FileChannel.open(file,
          StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
        try (var ignored = ch.lock()) { // lock de canal (coopera entre procesos Java)
          var buf = java.nio.ByteBuffer.wrap(lineWithNewline.getBytes(StandardCharsets.UTF_8));
          while (buf.hasRemaining()) ch.write(buf);
          ch.force(true); // fsync a disco
        }
      }
    }
  }

  private int txPartition(cc4p1.model.Transaction tx) {
    return partitioner.partForId(tx.origen()); // particiona por cuenta ORIGEN
  }

@Override
public void appendTransaccion(cc4p1.model.Transaction tx) {
  int p = txPartition(tx);
  Path file = transaccionesFile(p);

  Optional<cc4p1.model.Transaction> existing = findTxInPartition(tx.txId(), p);
  if (existing.isPresent()) {
    var e = existing.get();
    boolean sameCore =
        e.origen() == tx.origen() &&
        e.destino() == tx.destino() &&
        e.monto().compareTo(tx.monto()) == 0 &&
        java.util.Objects.equals(e.tipo(), tx.tipo());
    if (sameCore) {
      // Mismo txId y misma operación (ignorando ts) → idempotente (no-op)
      return;
    }
    // Mismo txId con operación distinta → conflicto
    throw new IllegalStateException("Conflicto de idempotencia: mismo txId con payload distinto " + tx.txId());
  }

  try {
    atomicAppendLine(file, tx.toCsv() + "\n");
  } catch (IOException e) {
    throw new UncheckedIOException(e);
  }
}

  @Override
  public Optional<cc4p1.model.Transaction> getTransaccionById(String txId) {
    for (int p = 0; p < partitioner.numParts(); p++) {
      var t = findTxInPartition(txId, p);
      if (t.isPresent()) return t;
    }
    return Optional.empty();
  }

  // (opcional) scan por partición para tests/aggregados
  public java.util.stream.Stream<cc4p1.model.Transaction> scanTransaccionesPartition(int p) {
    Path f = transaccionesFile(p);
    if (!Files.exists(f)) return java.util.stream.Stream.empty();
    try {
      var list = Files.readAllLines(f, StandardCharsets.UTF_8)
          .stream().skip(1).filter(s -> !s.isBlank())
          .map(s -> s.split(";"))
          .map(cc4p1.model.Transaction::fromCsv)
          .toList();
      return list.stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
  
    // Headers
    private static final String PRESTAMOS_HEADER = "id_prestamo;id_cliente;monto;tasa_anual;fecha;estado\n";
    private static final String PAGOS_HEADER     = "pay_id;ts;id_prestamo;monto\n";

    // Rutas
    private java.nio.file.Path prestamosFile(int p){ return base.resolve("partitions").resolve("prestamos_p"+p+".csv"); }
    private java.nio.file.Path pagosFile(int p){ return base.resolve("partitions").resolve("pagos_p"+p+".csv"); }
    
    // Inserta un préstamo (no-idempotente; asume idPrestamo único)
public void putPrestamo(cc4p1.model.Loan loan){
  int p = partitioner.partForId(loan.idCliente()); // particionar por cliente
  var file = prestamosFile(p);
  try {
    ensureFileWithHeader(file, PRESTAMOS_HEADER);
    String row = loan.toCsv() + "\n";
    synchronized (lockFor(file)) {
      java.nio.file.Files.write(file, row.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.APPEND);
    }
  } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
}

// Lista préstamos por cliente (rápido: lee solo su partición)
@Override
public java.util.stream.Stream<cc4p1.model.Loan> getPrestamosByCliente(long idCliente){
  int p = partitioner.partForId(idCliente);
  var file = prestamosFile(p);
  if (!java.nio.file.Files.exists(file)) return java.util.stream.Stream.empty();
  try {
    var list = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)
      .stream().skip(1).filter(l -> !l.isBlank())
      .map(l -> l.split(";"))
      .map(cc4p1.model.Loan::fromCsv)
      .filter(l -> l.idCliente() == idCliente)
      .toList();
    return list.stream();
  } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
}

// Append de pago (idempotencia opcional por payId: puedes replicar la lógica de transacciones)
public void appendPago(cc4p1.model.Payment pay){
  int p = partitioner.partForId(pay.idPrestamo()); // particionar por préstamo
  var file = pagosFile(p);
  try {
    ensureFileWithHeader(file, PAGOS_HEADER);
    String row = pay.toCsv() + "\n";
    synchronized (lockFor(file)) {
      java.nio.file.Files.write(file, row.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.APPEND);
    }
  } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
}

// Obtener pagos de un préstamo (lee solo su partición)
public java.util.stream.Stream<cc4p1.model.Payment> getPagosByPrestamo(long idPrestamo){
  int p = partitioner.partForId(idPrestamo);
  var file = pagosFile(p);
  if (!java.nio.file.Files.exists(file)) return java.util.stream.Stream.empty();
  try {
    var list = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)
      .stream().skip(1).filter(l -> !l.isBlank())
      .map(l -> l.split(";"))
      .map(cc4p1.model.Payment::fromCsv)
      .filter(pg -> pg.idPrestamo() == idPrestamo)
      .toList();
    return list.stream();
  } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
}


}
