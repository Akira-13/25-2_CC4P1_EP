package cc4p1.storage.replicated;

import cc4p1.model.Account;
import cc4p1.storage.FileStorage;
import cc4p1.storage.Partitioner;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public final class LocalFileNodeStorageClient implements NodeStorageClient {
  private final String nodeId;
  private final FileStorage storage;
  private final Partitioner partitioner;

  public LocalFileNodeStorageClient(String nodeId, Path nodeBase, int numParts) {
    this.nodeId = nodeId;
    this.storage = FileStorage.open(nodeBase, numParts); // asegúrate que FileStorage reciba base y numParts
    this.partitioner = new Partitioner(numParts);
  }

  @Override public String nodeId() { return nodeId; }
  @Override public Optional<Account> getCuenta(long idCuenta) { return storage.getCuenta(idCuenta); }
  @Override public void putCuenta(Account acc) { storage.putCuenta(acc); }
  @Override public Stream<Account> scanCuentasPartition(int p) { return storage.scanCuentasPartition(p); }
  @Override public BigDecimal arqueoSaldosPartition(int p) { return storage.arqueoSaldosPartition(p); }
}
