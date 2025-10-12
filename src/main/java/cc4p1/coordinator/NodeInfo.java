/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.coordinator;

/**
 *
 * @author Camila
 */
public class NodeInfo {
    private final String host;
    private final int port;
    private final int priority;
    
    public NodeInfo(String host, int port, int priority) {
        this.host = host;
        this.port = port;
        this.priority = priority;
    }
    
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public String toString() {
        return host + ":" + port + "(p=" + priority + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeInfo)) return false;
        NodeInfo node = (NodeInfo) o;
        return port == node.port && host.equals(node.host);
    }

    @Override
    public int hashCode() {
        return host.hashCode() * 31 + port;
    }
    
}
