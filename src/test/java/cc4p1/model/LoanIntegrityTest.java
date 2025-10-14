/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.model;

/**
 *
 * @author ak13a
 */
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LoanIntegrityTest {

  @Test
  void pendiente_resta_suma_de_pagos_y_no_es_negativa() {
    // nuevo: usa factory para que pendiente = monto y estado = ACTIVO
    Loan loan = Loan.newLoan(1L, 10L, new BigDecimal("1000"), new BigDecimal("0.25"), LocalDate.now());

    // sin pagos
    BigDecimal p0 = LoanUtils.saldoPendiente(loan, Stream.empty());
    assertEquals(new BigDecimal("1000"), p0);

    // dos pagos: 300 y 200
    var pagos12 = Stream.of(
        new Payment("p1", 1L, 1L, new BigDecimal("300")),
        new Payment("p2", 2L, 1L, new BigDecimal("200"))
    );
    BigDecimal p1 = LoanUtils.saldoPendiente(loan, pagos12);
    assertEquals(new BigDecimal("500"), p1);
    assertEquals(new BigDecimal("1000").subtract(new BigDecimal("300").add(new BigDecimal("200"))), p1);

    // opcional: verificar actualización de pendiente/estado
    Loan actualizado = LoanUtils.withPendienteActualizado(loan, pagos12);
    assertEquals(new BigDecimal("500"), actualizado.pendiente());
    assertEquals("ACTIVO", actualizado.estado());

    // pago que excede lo pendiente -> se trunca a 0
    var pagosOver = Stream.of(
        new Payment("p3", 3L, 1L, new BigDecimal("600")),
        new Payment("p4", 4L, 1L, new BigDecimal("500"))
    );
    BigDecimal p2 = LoanUtils.saldoPendiente(loan, pagosOver);
    assertEquals(BigDecimal.ZERO, p2);

    // opcional: con overpay el estado debe ser CANCELADO
    Loan cancelado = LoanUtils.withPendienteActualizado(loan, pagosOver);
    assertEquals(BigDecimal.ZERO, cancelado.pendiente());
    assertEquals("CANCELADO", cancelado.estado());
  }
}
