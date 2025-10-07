package cc4p1.storage.tools;

import cc4p1.model.Account;
import cc4p1.storage.FileStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

public final class SeedTool {
  public static void main(String[] args) {
    int numParts = 3;
    var storage = FileStorage.open(Path.of("data"), numParts);
    BigDecimal total = BigDecimal.ZERO;
    for(long id=1; id<=10_000; id++){
      BigDecimal saldo = BigDecimal.valueOf(1000);
      storage.putCuenta(new Account(id, id, saldo, LocalDate.now()));
      total = total.add(saldo);
    }
    System.out.println("TOTAL_SEED="+total);
  }
}
