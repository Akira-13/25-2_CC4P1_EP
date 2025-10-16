package cc4p1.coordinator.maintenance;

import cc4p1.coordinator.NodeInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class AccountVerifier {
    private final List<NodeInfo> replicas;
    private final HttpClient client;

    public AccountVerifier(List<NodeInfo> replicas) {
        this.replicas = new ArrayList<>(replicas);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
    }

    public VerifyResult verify(long accountId) {
        Map<String, List<NodeInfo>> checksums = new HashMap<>();
        List<NodeInfo> unreachable = new ArrayList<>();

        // ADD DEBUG: Track what we're checking
        System.err.printf("[AccountVerifier] Checking account %d across %d replicas%n", 
                         accountId, replicas.size());

        for (NodeInfo node : replicas) {
            try {
                String url = String.format("http://%s:%d/consultar_cuenta?id=%d",
                        node.getHost(), node.getPort(), accountId);
                
                System.err.printf("[AccountVerifier] Querying: %s%n", url);
                
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(800))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                
                System.err.printf("[AccountVerifier] Node %s:%d returned status=%d, body=%s%n",
                                 node.getHost(), node.getPort(), resp.statusCode(), 
                                 resp.body().substring(0, Math.min(100, resp.body().length())));
                
                if (resp.statusCode() == 200) {
                    String body = resp.body();
                    checksums.computeIfAbsent(body, k -> new ArrayList<>()).add(node);
                } else {
                    System.err.printf("[AccountVerifier] Non-200 status, marking unreachable%n");
                    unreachable.add(node);
                }
            } catch (Exception e) {
                System.err.printf("[AccountVerifier] Exception for node %s:%d - %s%n",
                                 node.getHost(), node.getPort(), e.getMessage());
                unreachable.add(node);
            }
        }

        System.err.printf("[AccountVerifier] Results: %d unique checksums, %d unreachable%n",
                         checksums.size(), unreachable.size());

        // Find majority response (2+ nodes with same data)
        String majorityChecksum = null;
        List<NodeInfo> majorityNodes = Collections.emptyList();
        List<NodeInfo> outliers = new ArrayList<>();

        for (Map.Entry<String, List<NodeInfo>> entry : checksums.entrySet()) {
            System.err.printf("[AccountVerifier] Checksum group: %d nodes%n", entry.getValue().size());
            if (entry.getValue().size() >= 2) {
                majorityChecksum = entry.getKey();
                majorityNodes = entry.getValue();
                break;
            }
        }

        if (majorityChecksum != null) {
            for (NodeInfo node : replicas) {
                if (!majorityNodes.contains(node) && !unreachable.contains(node)) {
                    outliers.add(node);
                }
            }
        }

        return new VerifyResult(majorityChecksum != null, majorityChecksum, 
                              majorityNodes, outliers, unreachable);
    }
}