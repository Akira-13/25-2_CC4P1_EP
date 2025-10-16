package cc4p1.coordinator.maintenance;

import cc4p1.coordinator.NodeInfo;
import java.util.List;

public class VerifyResult {
    private final boolean hasMajority;
    private final String majorityChecksum;
    private final List<NodeInfo> majority;
    private final List<NodeInfo> outliers;
    private final List<NodeInfo> unreachable;

    public VerifyResult(boolean hasMajority, String majorityChecksum,
                       List<NodeInfo> majority, List<NodeInfo> outliers,
                       List<NodeInfo> unreachable) {
        this.hasMajority = hasMajority;
        this.majorityChecksum = majorityChecksum;
        this.majority = majority;
        this.outliers = outliers;
        this.unreachable = unreachable;
    }

    public boolean needsRepair() {
        return hasMajority && (!outliers.isEmpty() || !unreachable.isEmpty());
    }

    // Add these getter methods
    public NodeInfo getMajorityNode() {
        return majority != null && !majority.isEmpty() ? majority.get(0) : null;
    }

    public List<NodeInfo> getOutliers() {
        return outliers;
    }

    public List<NodeInfo> getUnreachable() {
        return unreachable;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"hasMajority\":").append(hasMajority);
        if (majorityChecksum != null) {
            sb.append(",\"majorityChecksum\":\"").append(majorityChecksum.replace("\"", "\\\"")).append("\"");
        }
        appendNodeList(sb, "majority", majority);
        appendNodeList(sb, "outliers", outliers);
        appendNodeList(sb, "unreachable", unreachable);
        sb.append("}");
        return sb.toString();
    }

    private void appendNodeList(StringBuilder sb, String name, List<NodeInfo> nodes) {
        sb.append(",\"").append(name).append("\":[");
        boolean first = true;
        for (NodeInfo node : nodes) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"host\":\"").append(node.getHost())
              .append("\",\"port\":").append(node.getPort()).append("}");
        }
        sb.append("]");
    }
}