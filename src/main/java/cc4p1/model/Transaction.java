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
import java.time.LocalDate;

public record Transaction(
    String idTx,        // id_tx (idempotencia)
    long idCuenta,      // id_cuenta (cuenta afectada)
    String tipo,        // tipo (p.ej. "DEBITO", "CREDITO", "TRANSFER")
    BigDecimal monto,   // monto
    LocalDate fecha     // fecha (ISO yyyy-MM-dd)
) {
  // Helpers de fábrica
  public static Transaction debito(String idTx, long idCuenta, BigDecimal monto) {
    return new Transaction(idTx, idCuenta, "DEBITO", monto, LocalDate.now());
  }
  public static Transaction credito(String idTx, long idCuenta, BigDecimal monto) {
    return new Transaction(idTx, idCuenta, "CREDITO", monto, LocalDate.now());
  }

  // CSV: id_tx;id_cuenta;tipo;monto;fecha
  public String toCsv() {
    return idTx + ";" + idCuenta + ";" + tipo + ";" + monto + ";" + fecha;
  }

  public static Transaction fromCsv(String[] f) {
    return new Transaction(
        f[0],
        Long.parseLong(f[1]),
        f[2],
        new BigDecimal(f[3]),
        LocalDate.parse(f[4])
    );
  }
}