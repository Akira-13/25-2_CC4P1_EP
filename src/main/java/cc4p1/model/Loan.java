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

public record Loan(
    long idPrestamo,
    long idCliente,
    BigDecimal monto,        // principal otorgado
    BigDecimal tasaAnual,    // por ejemplo 0.25 para 25% anual (si se usa)
    LocalDate fecha,         // fecha de otorgamiento
    String estado            // "ACTIVO" | "CANCELADO"
) {
  public String toCsv() {
    return idPrestamo + ";" + idCliente + ";" + monto + ";" + tasaAnual + ";" + fecha + ";" + estado;
  }
  public static Loan fromCsv(String[] f) {
    return new Loan(
        Long.parseLong(f[0]),
        Long.parseLong(f[1]),
        new BigDecimal(f[2]),
        new BigDecimal(f[3]),
        LocalDate.parse(f[4]),
        f[5]
    );
  }
}
