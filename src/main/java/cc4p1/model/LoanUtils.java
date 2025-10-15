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
import java.util.stream.Stream;

public final class LoanUtils {
  private LoanUtils() {}

  /** Saldo pendiente simple = max(0, monto - sum(pagos)). (Sin intereses por ahora) */
  public static BigDecimal saldoPendiente(Loan loan, Stream<Payment> pagos) {
    BigDecimal pagado = pagos
        .map(Payment::monto)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal pendiente = loan.monto().subtract(pagado);
    return pendiente.signum() > 0 ? pendiente : BigDecimal.ZERO;
  }

  /** Estado sugerido a partir del saldo pendiente. */
  public static String estado(Loan loan, Stream<Payment> pagos) {
    return saldoPendiente(loan, pagos).signum() == 0 ? "CANCELADO" : "ACTIVO";
  }
}