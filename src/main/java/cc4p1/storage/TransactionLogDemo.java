package cc4p1.storage;

/**
 *
 * @author ak13a
 */
public class TransactionLogDemo {
  public static void main(String[] args) throws Exception {
    // 1) Wiring básico (replicado)
    var part = new cc4p1.storage.Partitioner(3);
    var selector = new cc4p1.storage.replicas.ReplicaSelectorProperties(
        java.nio.file.Path.of("data/metadata/replicas.properties"));
    var nodes = new java.util.HashMap<String, cc4p1.storage.replicated.NodeStorageClient>();
    nodes.put("nodeA", new cc4p1.storage.replicated.LocalFileNodeStorageClient("nodeA", java.nio.file.Path.of("data/nodeA"), 3));
    nodes.put("nodeB", new cc4p1.storage.replicated.LocalFileNodeStorageClient("nodeB", java.nio.file.Path.of("data/nodeB"), 3));
    nodes.put("nodeC", new cc4p1.storage.replicated.LocalFileNodeStorageClient("nodeC", java.nio.file.Path.of("data/nodeC"), 3));
    cc4p1.storage.Storage st = new cc4p1.storage.replicated.ReplicatedStorage(part, selector, nodes);

    // 2) Encontrar un id_cuenta que caiga en cada partición p0, p1, p2
    long idP0 = -1, idP1 = -1, idP2 = -1;
    for (long id = 1; id < 10_000 && (idP0 < 0 || idP1 < 0 || idP2 < 0); id++) {
      int p = part.partForId(id);
      if (p == 0 && idP0 < 0) idP0 = id;
      if (p == 1 && idP1 < 0) idP1 = id;
      if (p == 2 && idP2 < 0) idP2 = id;
    }
    if (idP0 < 0 || idP1 < 0 || idP2 < 0) {
      throw new IllegalStateException("No se encontraron IDs para las tres particiones");
    }
    System.out.println("IDs por partición → p0=" + idP0 + " p1=" + idP1 + " p2=" + idP2);

    // 3) Registrar un asiento en cada partición (txId únicos)
    st.appendTransaccion(cc4p1.model.Transaction.debito(java.util.UUID.randomUUID().toString(), idP0, new java.math.BigDecimal("10.00")));
    st.appendTransaccion(cc4p1.model.Transaction.credito(java.util.UUID.randomUUID().toString(), idP1, new java.math.BigDecimal("20.00")));
    st.appendTransaccion(cc4p1.model.Transaction.credito(java.util.UUID.randomUUID().toString(), idP2, new java.math.BigDecimal("30.00")));

    // 4) Mensaje útil: en qué archivos deberías ver los registros (en los 3 nodos)
    System.out.println("Esperado:");
    System.out.println(" - transacciones_p" + part.partForId(idP0) + ".csv");
    System.out.println(" - transacciones_p" + part.partForId(idP1) + ".csv");
    System.out.println(" - transacciones_p" + part.partForId(idP2) + ".csv");
    System.out.println("OK");
  }
}
