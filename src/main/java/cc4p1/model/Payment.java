/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.model;
import java.math.BigDecimal;
/**
 *
 * @author ak13a
 */
public record Payment(
    String payId,       // idempotencia del pago (opcional en P1, útil si haces reintentos)
    long tsEpochMillis, // instante del pago
    long idPrestamo,
    BigDecimal monto
) {
  public String toCsv() {
    return payId + ";" + tsEpochMillis + ";" + idPrestamo + ";" + monto;
  }
  public static Payment fromCsv(String[] f) {
    return new Payment(
        f[0],
        Long.parseLong(f[1]),
        Long.parseLong(f[2]),
        new java.math.BigDecimal(f[3])
    );
  }
}
