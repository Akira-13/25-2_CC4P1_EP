package cc4p1.storage;

/**
 *
 * @author ak13a
 */
import cc4p1.model.Account;
import cc4p1.storage.replicas.ReplicaSelectorProperties;
import cc4p1.storage.replicated.LocalFileNodeStorageClient;
import cc4p1.storage.replicated.NodeStorageClient;
import cc4p1.storage.replicated.ReplicatedStorage;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que el total de saldos (arqueo) se conserve
 * antes y después de aplicar transferencias.
 */
class GlobalIntegrityTest {

  static Path BASE = Path.of("data");
  static Path META = BASE.resolve("metadata").resolve("replicas.properties");

  private Storage newStorage() {
    var part = new Partitioner(3);
    var selector = new ReplicaSelectorProperties(META);
    var nodes = new HashMap<String, NodeStorageClient>();
    nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", BASE.resolve("nodeA"), 3));
    nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", BASE.resolve("nodeB"), 3));
    nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", BASE.resolve("nodeC"), 3));
    return new ReplicatedStorage(part, selector, nodes);
  }

  @Test
  void total_de_saldos_se_conserva_tras_transferencias() {
    Storage st = newStorage();

    // 1) Estado inicial (puedes crear dos cuentas locales)
    Account a1 = new Account(1L, 100L, new BigDecimal("500"), LocalDate.now());
    Account a2 = new Account(2L, 200L, new BigDecimal("300"), LocalDate.now());
    st.putCuenta(a1);
    st.putCuenta(a2);

    BigDecimal totalAntes = st.arqueoSaldos();

    // 2) Aplica una transferencia manualmente (simulada en el test)
    BigDecimal monto = new BigDecimal("100");
    a1 = new Account(a1.id(), a1.idCliente(), a1.saldo().subtract(monto), a1.fechaApertura());
    a2 = new Account(a2.id(), a2.idCliente(), a2.saldo().add(monto), a2.fechaApertura());
    st.putCuenta(a1);
    st.putCuenta(a2);

    BigDecimal totalDespues = st.arqueoSaldos();

    // 3) Verifica conservación
    assertEquals(totalAntes, totalDespues, "El total de saldos debe conservarse tras las transferencias");
  }
}