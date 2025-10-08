package cc4p1.storage;

import cc4p1.model.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

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
        String row = acc.toCsv() + "\n";
        // lock por archivo/partición
        synchronized (lockFor(file)) {
          Files.write(file, row.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND);
        }
      } catch(IOException e){ throw new UncheckedIOException(e); }
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
  @Override public Stream<Loan> getPrestamosByCliente(long id){ return Stream.empty(); }
  @Override public void appendTransaccion(Transaction tx){ /* S2 */ }
}
