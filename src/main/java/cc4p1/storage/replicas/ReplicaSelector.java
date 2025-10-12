package cc4p1.storage.replicas;

import java.util.List;

public interface ReplicaSelector {
  String primary(String table, int partition);
  List<String> readOrder(String table, int partition); // primario + réplicas en orden
  int replicaCount(String table, int partition);
}
