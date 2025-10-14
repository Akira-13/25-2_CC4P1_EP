/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.model;

/**
 *
 * @author ak13a
 */
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record Transaction(
    String txId,          // idempotencia
    long tsEpochMillis,   // timestamp de emisión
    long origen,          // cuenta origen
    long destino,         // cuenta destino
    BigDecimal monto,     // monto
    String tipo           // p.ej. "TRANSFER"
) {
  public static Transaction transfer(String txId, long origen, long destino, BigDecimal monto) {
    return new Transaction(Objects.requireNonNull(txId), Instant.now().toEpochMilli(),
                           origen, destino, Objects.requireNonNull(monto), "TRANSFER");
  }

  public String toCsv() {
    return txId + ";" + tsEpochMillis + ";" + origen + ";" + destino + ";" + monto + ";" + tipo;
  }

  public static Transaction fromCsv(String[] f) {
    return new Transaction(
        f[0],
        Long.parseLong(f[1]),
        Long.parseLong(f[2]),
        Long.parseLong(f[3]),
        new BigDecimal(f[4]),
        f[5]
    );
  }
}