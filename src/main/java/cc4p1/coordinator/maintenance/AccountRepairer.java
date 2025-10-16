package cc4p1.coordinator.maintenance;

import cc4p1.coordinator.NodeInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Repara réplicas inconsistentes copiando los datos desde un nodo correcto.
 */
public class AccountRepairer {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public RepairResult repair(VerifyResult verifyResult, long accountId) {
        if (!verifyResult.needsRepair()) {
            return new RepairResult(true, null, Collections.emptyList());
        }

        List<NodeInfo> repairedNodes = new ArrayList<>();
        String error = null;

        // Obtener datos fuente del primer nodo de la mayoría
        NodeInfo sourceNode = verifyResult.getMajorityNode();
        String sourceData = fetchAccountData(sourceNode, accountId);
        
        if (sourceData == null) {
            return new RepairResult(false, "No se pudo obtener los datos fuente", Collections.emptyList());
        }

        // Copiar a todos los nodos outliers e inalcanzables
        List<NodeInfo> toRepair = new ArrayList<>();
        toRepair.addAll(verifyResult.getOutliers());
        toRepair.addAll(verifyResult.getUnreachable());

        for (NodeInfo node : toRepair) {
            try {
                // Usar PUT para forzar la actualización
                String url = String.format("http://%s:%d/admin/repair_account?id=%d",
                        node.getHost(), node.getPort(), accountId);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(1))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(sourceData))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    repairedNodes.add(node);
                }
            } catch (Exception e) {
                // Continuar con otros nodos
            }
        }

        boolean success = repairedNodes.size() == toRepair.size();
        return new RepairResult(success, error, repairedNodes);
    }

    private String fetchAccountData(NodeInfo node, long accountId) {
        try {
            String url = String.format("http://%s:%d/consultar_cuenta?id=%d",
                    node.getHost(), node.getPort(), accountId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}