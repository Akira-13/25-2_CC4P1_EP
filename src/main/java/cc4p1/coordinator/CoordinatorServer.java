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
import cc4p1.storage.Partitioner;
import java.util.concurrent.Executors;

public class CoordinatorServer {

    private static final int PORT = 8080;
    // Debe coincidir con el particionador y replicas.properties (SeedReplicated
    // crea 3 por defecto)
    private static final int NUM_PARTITIONS = 3;
    private static final RoutingTable routingTable = new RoutingTable(NUM_PARTITIONS);
    // Particionador compartido para que el coordinator calcule la misma partición
    private static final Partitioner PARTITIONER = new Partitioner(NUM_PARTITIONS);

    // Métricas simples en memoria
    private static final java.util.concurrent.atomic.AtomicLong reqTotal = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong fallbacksTotal = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong errorsTotal = new java.util.concurrent.atomic.AtomicLong(0);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/register", new RegisterHandler());
        server.createContext("/consultar_cuenta", new ConsultarCuentaHandler());
        server.createContext("/transferir_cuenta", new TransferirCuentaHandler());
        server.createContext("/estado_prestamo", new EstadoPrestamoHandler());
        server.createContext("/routing", new RoutingHandler());
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/metrics", new MetricsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("[Coordinator] Servidor iniciado en puerto " + PORT);
    }

    static class RoutingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }

            Map<Integer, List<NodeInfo>> snap = routingTable.snapshot();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"routing\":{");
            boolean firstPart = true;
            for (var e : snap.entrySet()) {
                if (!firstPart)
                    sb.append(',');
                firstPart = false;
                sb.append('"').append(e.getKey()).append('"').append(':');
                sb.append('[');
                boolean firstNode = true;
                for (NodeInfo n : e.getValue()) {
                    if (!firstNode)
                        sb.append(',');
                    firstNode = false;
                    sb.append('{')
                            .append("\"host\":\"").append(n.getHost()).append("\"")
                            .append(',')
                            .append("\"port\":").append(n.getPort())
                            .append(',')
                            .append("\"priority\":").append(n.getPriority())
                            .append('}');
                }
                sb.append(']');
            }
            sb.append("}});");
            sendResponse(exchange, 200, sb.toString());
        }
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
            reqTotal.incrementAndGet();
            long start = System.currentTimeMillis();
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(exchange.getRequestURI());
            int id = Integer.parseInt(q.getOrDefault("id", "-1"));
            int partition = PARTITIONER.partForId(id);
            List<NodeInfo> replicas = routingTable.getReplicas(partition);

            if (replicas.isEmpty()) {
                errorsTotal.incrementAndGet();
                System.out.println("[Coordinator] No hay réplicas para la partición " + partition + ". Snapshot: "
                        + routingTable.snapshot());
                sendResponse(exchange, 503, "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}");
                return;
            }

            String body = WorkerForwarder.forwardQuery(replicas, id);
            long duration = System.currentTimeMillis() - start;
            System.out.printf("[Coordinator] GET /consultar_cuenta?id=%d → partition=%d, duration=%dms%n",
                    id, partition, duration);
            sendResponse(exchange, 200, body);
        }
    }

    static class TransferirCuentaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            reqTotal.incrementAndGet();
            long start = System.currentTimeMillis();

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 405, "{\"ok\":false,\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(exchange.getRequestURI());
            String origenStr = q.get("origen");
            String destinoStr = q.get("destino");
            String montoStr = q.get("monto");
            String txId = q.get("txId");

            // Validaciones
            if (origenStr == null || destinoStr == null || montoStr == null || txId == null || txId.isBlank()) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Faltan parámetros: origen, destino, monto, txId\"}");
                return;
            }

            try {
                int origen = Integer.parseInt(origenStr);
                int destino = Integer.parseInt(destinoStr);
                double monto = Double.parseDouble(montoStr);

                if (monto <= 0) {
                    errorsTotal.incrementAndGet();
                    sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Monto debe ser mayor a 0\"}");
                    return;
                }

                // Partición del origen
                int partition = PARTITIONER.partForId(origen);
                List<NodeInfo> replicas = routingTable.getReplicas(partition);

                if (replicas.isEmpty()) {
                    errorsTotal.incrementAndGet();
                    System.out.printf("[Coordinator] Transfer txId=%s: No hay réplicas para partición %d%n", txId, partition);
                    sendResponse(exchange, 503, "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}");
                    return;
                }

                // Solo intentamos el primario (priority=0)
                NodeInfo primary = replicas.get(0);
                System.out.printf("[Coordinator] POST /transferir_cuenta txId=%s origen=%d destino=%d monto=%.2f → partition=%d, primary=%s%n",
                        txId, origen, destino, monto, partition, primary);

                String body = WorkerForwarder.forwardTransfer(primary, origen, destino, monto, txId);
                long duration = System.currentTimeMillis() - start;

                // Determinar código de respuesta basado en el body
                int responseCode = determineResponseCode(body);
                if (responseCode >= 400) {
                    errorsTotal.incrementAndGet();
                }

                System.out.printf("[Coordinator] Transfer txId=%s completado con código %d en %dms%n",
                        txId, responseCode, duration);
                sendResponse(exchange, responseCode, body);

            } catch (NumberFormatException e) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Parámetros numéricos inválidos\"}");
            }
        }
    }

    static class EstadoPrestamoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            reqTotal.incrementAndGet();
            long start = System.currentTimeMillis();

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 405, "{\"ok\":false,\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(exchange.getRequestURI());
            String idStr = q.get("id");

            if (idStr == null) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Falta parámetro id\"}");
                return;
            }

            try {
                int cuentaId = Integer.parseInt(idStr);
                int partition = PARTITIONER.partForId(cuentaId);
                List<NodeInfo> replicas = routingTable.getReplicas(partition);

                if (replicas.isEmpty()) {
                    errorsTotal.incrementAndGet();
                    System.out.printf("[Coordinator] Prestamo id=%d: No hay réplicas para partición %d%n", cuentaId, partition);
                    sendResponse(exchange, 503, "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}");
                    return;
                }

                System.out.printf("[Coordinator] GET /estado_prestamo?id=%d → partition=%d, replicas=%s%n",
                        cuentaId, partition, replicas);

                String body = WorkerForwarder.forwardPrestamo(replicas, cuentaId);
                long duration = System.currentTimeMillis() - start;

                // Contar fallbacks si se intentó más de un nodo
                if (replicas.size() > 1 && body.contains("\"ok\":true")) {
                    fallbacksTotal.incrementAndGet();
                }

                int responseCode = determineResponseCode(body);
                if (responseCode >= 400) {
                    errorsTotal.incrementAndGet();
                }

                System.out.printf("[Coordinator] Prestamo id=%d completado con código %d en %dms%n",
                        cuentaId, responseCode, duration);
                sendResponse(exchange, responseCode, body);

            } catch (NumberFormatException e) {
                errorsTotal.incrementAndGet();
                sendResponse(exchange, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Parámetro id inválido\"}");
            }
        }
    }

    static class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = String.format(
                    "{\"ok\":true,\"metrics\":{\"req_total\":%d,\"fallbacks_total\":%d,\"errors_total\":%d}}",
                    reqTotal.get(), fallbacksTotal.get(), errorsTotal.get());
            sendResponse(exchange, 200, response);
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
        if (query == null)
            return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                map.put(
                        URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    /**
     * Determina el código de respuesta HTTP basado en el contenido del body JSON.
     */
    static int determineResponseCode(String body) {
        if (body.contains("\"ok\":true")) {
            return 200;
        }
        if (body.contains("\"error\":\"CUENTA_NO_EXISTE\"") || body.contains("\"error\":\"NOT_FOUND\"") 
                || body.contains("\"error\":\"CUENTA_SIN_PRESTAMOS\"")) {
            return 404;
        }
        if (body.contains("\"error\":\"SALDO_INSUFICIENTE\"") || body.contains("\"error\":\"TX_DUPLICADA\"")) {
            return 409;
        }
        if (body.contains("\"error\":\"VALIDACION\"")) {
            return 400;
        }
        if (body.contains("\"error\":\"NODOS_NO_DISPONIBLES\"")) {
            return 503;
        }
        // Default para otros errores
        return 500;
    }
}
