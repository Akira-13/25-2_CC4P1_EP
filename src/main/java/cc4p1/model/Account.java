package cc4p1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Account(long id, long idCliente, BigDecimal saldo, LocalDate fechaApertura) {
  public String toCsv(){
    return id+";"+idCliente+";"+saldo+";"+fechaApertura;
  }
  public static Account fromCsv(String[] f){
    return new Account(
      Long.parseLong(f[0]),
      Long.parseLong(f[1]),
      new BigDecimal(f[2]),
      java.time.LocalDate.parse(f[3])
    );
  }
}
