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
    BigDecimal tasaAnual,    // p.ej. 0.25 para 25% anual (no usada en P1)
    LocalDate fecha,         // fecha de otorgamiento
    BigDecimal pendiente,    // NUEVO: saldo pendiente
    String estado            // "ACTIVO" | "CANCELADO"
) {
  /** Fábrica conveniente: al crear, pendiente = monto y estado = ACTIVO. */
  public static Loan newLoan(long idPrestamo, long idCliente, BigDecimal monto, BigDecimal tasaAnual, LocalDate fecha) {
    return new Loan(idPrestamo, idCliente, monto, tasaAnual, fecha, monto, "ACTIVO");
  }

  /** CSV en el orden: id_prestamo;id_cliente;monto;tasa_anual;fecha;pendiente;estado */
  public String toCsv() {
    return idPrestamo + ";" + idCliente + ";" + monto + ";" + tasaAnual + ";" + fecha + ";" + pendiente + ";" + estado;
  }

  /** Compatibilidad: si vienen 6 columnas (sin 'pendiente'), lo asume igual a 'monto'. */
  public static Loan fromCsv(String[] f) {
    if (f.length >= 7) {
      return new Loan(
          Long.parseLong(f[0]),
          Long.parseLong(f[1]),
          new BigDecimal(f[2]),
          new BigDecimal(f[3]),
          LocalDate.parse(f[4]),
          new BigDecimal(f[5]),
          f[6]
      );
    } else if (f.length == 6) { // legacy: sin 'pendiente'
      BigDecimal monto = new BigDecimal(f[2]);
      return new Loan(
          Long.parseLong(f[0]),
          Long.parseLong(f[1]),
          monto,
          new BigDecimal(f[3]),
          LocalDate.parse(f[4]),
          monto,      // pendiente = monto
          f[5]
      );
    } else {
      throw new IllegalArgumentException("Formato CSV de Loan inválido. Esperado 7 (o 6 legacy) columnas.");
    }
  }
}