package cc4p1.storage;

import cc4p1.model.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

public interface Storage {
  Optional<Account> getCuenta(long idCuenta);
  Stream<Transaction> getTransaccionesByCuenta(long idCuenta);
  Stream<Loan> getPrestamosByCliente(long idCliente);

  void putCuenta(Account acc);            // usado por SeedTool
  void appendTransaccion(Transaction tx); // se usará desde S2

  BigDecimal arqueoSaldos();
  
   default Optional<Transaction> getTransaccionById(String txId) {
    throw new UnsupportedOperationException("getTransaccionById not implemented");
  }
}
