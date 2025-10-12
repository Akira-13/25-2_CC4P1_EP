/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.coordinator;

import java.util.*;

/**
 *
 * @author Camila
 */
public class RoutingTable {
    private final int numPartitions;
    private final Map<Integer, List<NodeInfo>> table;

    public RoutingTable(int numPartitions) {
        this.numPartitions = numPartitions;
        this.table = new HashMap<>();
        for (int i = 0; i < numPartitions; i++) {
            table.put(i, new ArrayList<>());  
        }
    }
    
    public synchronized void registerNode(String host, int port, List<Integer> partitions, String role) {
        int priority = "primary".equalsIgnoreCase(role) ? 0 : 1;

        for (int part : partitions) {
            if (!table.containsKey(part)) continue;

            List<NodeInfo> replicas = table.get(part);

            replicas.removeIf(n -> n.getHost().equals(host) && n.getPort() == port);

            replicas.add(new NodeInfo(host, port, priority));
            replicas.sort(Comparator.comparingInt(NodeInfo::getPriority));

            System.out.printf("[RoutingTable] Partición %d → %s%n", part, replicas);
        }
    }
    
    public synchronized List<NodeInfo> getReplicas(int partition) {
        return table.getOrDefault(partition, Collections.emptyList());
    }
    
    public synchronized Map<Integer, List<NodeInfo>> snapshot() {
        Map<Integer, List<NodeInfo>> copy = new HashMap<>();
        for (var e : table.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }
    
    public int getNumPartitions() {
        return numPartitions;
    }
}
