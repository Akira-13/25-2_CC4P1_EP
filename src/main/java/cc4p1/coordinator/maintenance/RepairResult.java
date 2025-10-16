package cc4p1.coordinator.maintenance;

import cc4p1.coordinator.NodeInfo;
import java.util.List;

/**
 * Resultado de la operación de reparación de réplicas.
 */
public class RepairResult {
    private final boolean success;
    private final String error;
    private final List<NodeInfo> repairedNodes;

    public RepairResult(boolean success, String error, List<NodeInfo> repairedNodes) {
        this.success = success;
        this.error = error;
        this.repairedNodes = repairedNodes;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"success\":").append(success);
        if (error != null) {
            sb.append(",\"error\":\"").append(error.replace("\"", "\\\"")).append("\"");
        }
        sb.append(",\"repairedNodes\":[");
        boolean first = true;
        for (NodeInfo node : repairedNodes) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"host\":\"").append(node.getHost())
              .append("\",\"port\":").append(node.getPort()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}