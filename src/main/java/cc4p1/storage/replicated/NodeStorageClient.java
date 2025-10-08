package cc4p1.storage.replicated;

import cc4p1.model.Account;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

public interface NodeStorageClient {
  String nodeId();
  Optional<Account> getCuenta(long idCuenta);
  void putCuenta(Account acc);

  // Para SCAN por partición (evitar duplicados cuando se agregan réplicas)
  Stream<Account> scanCuentasPartition(int partition);
  BigDecimal arqueoSaldosPartition(int partition); // opcional
}
