/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.coordinator;

/**
 *
 * @author Camila
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class CoordinatorServer {

    private static final int PORT = 8080;
    private static final int NUM_PARTITIONS = 6;
    private static final RoutingTable routingTable = new RoutingTable(NUM_PARTITIONS);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/register", new RegisterHandler());
        server.createContext("/consultar_cuenta", new ConsultarCuentaHandler());
        server.createContext("/healthz", new HealthHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("[Coordinator] Servidor iniciado en puerto " + PORT);
    }

    // --- Handlers ---

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"ok\":true,\"msg\":\"coordinator up\"}";
            sendResponse(exchange, 200, response);
        }
    }

    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(exchange.getRequestURI());
            String host = q.get("host");
            int port = Integer.parseInt(q.getOrDefault("port", "0"));
            String role = q.getOrDefault("role", "replica");
            List<Integer> parts = new ArrayList<>();
            if (q.containsKey("partitions")) {
                for (String s : q.get("partitions").split(",")) {
                    parts.add(Integer.valueOf(s.trim()));
                }
            }

            routingTable.registerNode(host, port, parts, role);
            sendResponse(exchange, 200, "{\"ok\":true,\"msg\":\"nodo registrado\"}");
        }
    }

    static class ConsultarCuentaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(exchange.getRequestURI());
            int id = Integer.parseInt(q.getOrDefault("id", "-1"));
            int partition = Math.floorMod(id, NUM_PARTITIONS);
            List<NodeInfo> replicas = routingTable.getReplicas(partition);

            if (replicas.isEmpty()) {
                sendResponse(exchange, 503, "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}");
                return;
            }

            // Esto lo implementaremos luego
            String body = WorkerForwarder.forwardQuery(replicas, id);
            sendResponse(exchange, 200, body);
        }
    }

    static void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.getBytes().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body.getBytes());
        }
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                map.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                );
            }
        }
        return map;
    }
}
