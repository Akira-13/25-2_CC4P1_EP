package cc4p1.storage;

import cc4p1.model.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class FileStorage implements Storage {
  private final Path base; private final Partitioner partitioner;

  private FileStorage(Path base, int numParts){
    this.base = base; this.partitioner = new Partitioner(numParts);
  }
  public static FileStorage open(Path base, int numParts){
    return new FileStorage(base, numParts);
  }

  @Override public Optional<Account> getCuenta(long id){
    int p = partitioner.partForId(id);
    Path file = base.resolve("partitions").resolve("cuentas_p"+p+".csv");
    try(BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)){
      String line = br.readLine(); // header
      while((line = br.readLine()) != null){
        String[] f = line.split(";");
        if(Long.parseLong(f[0]) == id) return Optional.of(Account.fromCsv(f));
      }
      return Optional.empty();
    } catch(IOException e){ throw new UncheckedIOException(e); }
  }

  @Override public void putCuenta(Account acc){
    int p = partitioner.partForId(acc.id());
    Path file = base.resolve("partitions").resolve("cuentas_p"+p+".csv");
    try{
      Files.createDirectories(file.getParent());
      if(!Files.exists(file)){
        // header
        String header = "id_cuenta;id_cliente;saldo;fecha_apertura\n";
        Files.write(file, header.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
      String row = acc.toCsv()+"\n";
      Files.write(file, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    } catch(IOException e){ throw new UncheckedIOException(e); }
  }

  @Override public BigDecimal arqueoSaldos(){
    try {
      BigDecimal total = BigDecimal.ZERO;
      for(int p=0; p<partitioner.numParts(); p++){
        Path file = base.resolve("partitions").resolve("cuentas_p"+p+".csv");
        if(!Files.exists(file)) continue;
        try(BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)){
          String line = br.readLine(); // header
          while((line = br.readLine()) != null){
            String[] f = line.split(";");
            total = total.add(new BigDecimal(f[2]));
          }
        }
      }
      return total;
    } catch(IOException e){ throw new UncheckedIOException(e); }
  }

  @Override public Stream<Transaction> getTransaccionesByCuenta(long id){ return Stream.empty(); }
  @Override public Stream<Loan> getPrestamosByCliente(long id){ return Stream.empty(); }
  @Override public void appendTransaccion(Transaction tx){ /* S2 */ }
}
